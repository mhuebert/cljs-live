#!/usr/bin/env planck
(ns script.bundle
  (:require [planck.core :refer [transfer-ns init-empty-state file-seq *command-line-args* slurp spit line-seq *in*]]
            [planck.repl :as repl]
            [planck.js-deps :as js-deps]
            [clojure.string :as string]
            [clojure.set :as set]
            [cljs.js :as cljsjs]
            [clojure.string :as string]
            [cljs.tools.reader :as r]
            [cljs.pprint :refer [pprint]]
            [script.goog-deps :as goog]
            [script.io :refer [->transit transit->clj realize-lazy-map]]))

(defn log [& args]
  #_(.error js/console args))

(defn get-named-arg [name]
  (second (first (filter #(= (str "--" name) (first %)) (partition 2 1 *command-line-args*)))))

(def extra-paths (atom []))

(def user-dir (get-named-arg "user_dir"))

(defn resource
  "Loads the content for a given file. Includes planck cache path."
  [file]
  (first (or (js/PLANCK_READ_FILE file)
             (js/PLANCK_LOAD file)
             (first (for [prefix @extra-paths
                          :let [path (str prefix "/" file)
                                source (js/PLANCK_READ_FILE path)]
                          :when source]
                      source))
             (js/PLANCK_READ_FILE (str (:cache-path @repl/app-env) "/" (munge file))))))

(defn ns->path
  ([s] (ns->path s ""))
  ([s ext]
   (-> (str s)
       (string/replace #"\.macros$" "$macros")
       munge
       (string/replace "." "/")
       (str ext))))

(defn path->ns [path]
  (-> path
      (string/replace ".cache.json" "")
      demunge
      (string/replace "/" ".")))

(defn cache-str
  "Look everywhere to find a cache file."
  [namespace]
  (or (resource (ns->path namespace ".cljs.cache.json"))
      (resource (ns->path namespace ".cljc.cache.json"))
      (first (doall (for [ext [".cljc" ".cljs"]
                          [format f] [["edn" (comp ->transit r/read-string)]
                                      ["json" identity]]
                          :let [path (ns->path namespace (str ext ".cache." format))
                                resource (some-> (resource path) f)]
                          :when resource]
                      resource)))
      (some-> (get-in @repl/st [:cljs.analyzer/namespaces namespace])
              realize-lazy-map
              (->transit))))

(def cache-map
  (memoize (fn [namespace]
             (or (some-> (get-in @repl/st [:cljs.analyzer/namespaces namespace])
                         realize-lazy-map)
                 (some-> (or (resource (ns->path namespace ".cljs.cache.json"))
                             (resource (ns->path namespace ".cljc.cache.json")))
                         (transit->clj))))))

(assert (= (mapv ns->path ['my-app.core 'my-app.core$macros 'my-app.some-lib])
           ["my_app/core" "my_app/core$macros" "my_app/some_lib"]))
(defn get-deps
  [ana-ns]
  (->> (select-keys ana-ns [:requires :imports])
       vals
       (keep identity)
       (map vals)
       (apply concat)
       (concat (->> ana-ns :require-macros keys (map #(symbol (str % "$macros")))))))
(def js-index js-deps/foreign-libs-index)
;; modified, from Planck

(defn topo-sorted-deps
  "Given a dep symbol to load, returns a topologically sorted sequence of deps to load, in load order."
  ([dep] (topo-sorted-deps dep #{}))
  ([dep found]
   (let [requires (filter (complement found) (get-deps (cache-map dep)))]
     (set (concat (mapcat #(topo-sorted-deps % (into found requires)) requires) [dep])))))

(defn files-to-load*
  "Returns the files to load given and index and a foreign libs dep."
  ([dep] (files-to-load* false dep))
  ([min? dep]
   (map #(or (when min? (:file-min (% @js-index)))
             (:file (% @js-index))) (js-deps/topo-sorted-deps @js-index dep))))
(def foreign-lib? (set (keys @js-index)))
(defn foreign-lib-src [dep]
  (apply str (->> (files-to-load* dep)
                  (map resource))))
(defn macro-str [namespace]
  (or (resource (ns->path namespace ".clj"))
      (resource (ns->path namespace ".cljc"))))

(defn expose-browser-global
  "Writes contents of map to name in `window`."
  [name payload]
  (let [export-var (str "window['" name "']")]
    (str export-var " = " export-var " || {}; "
         (reduce-kv (fn [s path src]
                      (str s export-var "['" path "'] = " (js/JSON.stringify (clj->js src)) ";")) "" payload))))
(defn patch-planck-js-eval
  "Instrument planck's js-eval to ignore exceptions; we are only interested in build artefacts,
  and some runtime errors occur because Planck is not a browser environment."
  []
  (cljsjs/eval repl/st
               '(defn js-eval
                  [source source-url]
                  (if source-url
                    (let [exception (js/PLANCK_EVAL source source-url)]
                      (when exception
                        ;; ignore exceptions, we only want build artefacts
                        #_(throw exception)))
                    (try (js/eval source)
                         (catch js/Error e
                           ;; ignore exceptions, we only want build artefacts
                           nil))))
               {:ns 'planck.repl}
               #(when (:error %) (prn %))))

(defn planck-require [type n]
  (let [form (with-meta `(~'ns ~'cljs.user (~type ~n))
                        {:merge true :line 1 :column 1})]
    (cljs.js/eval repl/st form {} #(when-let [e (:error %)] (println "\n\nfailed: " form "\n" e)))))

(defn calculate-deps [dep-spec]

  ;; require target deps into Planck for AOT analysis & compilation
  (doall (for [type [:require :require-macros :import]
               n (get dep-spec type)]
           (planck-require type n)))


  (let [{error    :error
         provided :value} (-> (line-seq *in*)               ;; provided namespaces may be passed via stdin, as :value in a map
                              (string/join)
                              (r/read-string))
        provided (set provided)]

    (when error (println :cljs-provided-error error))

    (->> (set (topo-sorted-deps 'cljs.user))                ;; using Planck's analysis cache, we calculate the full graph of required namespaces
         (group-by #(let [provided? (contains? provided %)]
                     (cond (contains? #{'cljs.core 'cljs.core$macros} %) :require-cache
                           (= 'cljs.env %) nil
                           (goog/goog? %) (if provided? :provided-goog :require-goog)
                           (foreign-lib? %) (when-not provided? :require-foreign-dep)
                           (not provided?) :require-source
                           :else :require-cache)))
         (#(dissoc % nil))
         ;; merge explicit requires and excludes mentioned in dep-spec
         (merge-with into (select-keys dep-spec [:require-source
                                                 :require-cache
                                                 :require-foreign-dep
                                                 :require-goog]))
         (merge-with #(remove (set %1) %2) (->> (select-keys dep-spec [:exclude-source
                                                                       :exclude-cache
                                                                       :exclude-foreign-dep
                                                                       :exclude-goog])
                                                (reduce-kv
                                                  #(assoc %1 (-> (name %2)
                                                                 (string/replace "exclude" "require")
                                                                 keyword) %3) {})))
         (#(update % :require-cache into '[cljs.core cljs.core$macros])))))

(defn bundle-deps [{:keys [require-source
                           require-cache
                           require-foreign-dep
                           require-goog
                           provided-goog]}]

  (let [provided-goog-files (set (mapcat goog/goog-dep-files provided-goog))

        caches (reduce (fn [m namespace]
                         (let [cache (cache-str namespace)]
                           (cond-> m
                                   cache (assoc (str (ns->path namespace) ".cache.json") cache)))) {} require-cache)
        sources (reduce (fn [m namespace]
                          (let [path (ns->path namespace)
                                source (resource (str path ".js"))
                                cache (cache-str namespace)]
                            (cond-> m
                                    source (assoc (str path ".js") source)
                                    cache (assoc (str path ".cache.json") cache)))) {} require-source)
        foreign-deps (reduce (fn [m namespace]
                               (assoc m (str (ns->path namespace) ".js")
                                        (foreign-lib-src namespace))) {} require-foreign-dep)
        goog (reduce (fn [m goog-file]
                       (cond-> m
                               (not (contains? provided-goog-files goog-file))
                               (-> (assoc goog-file (resource goog-file))
                                   (update "preload_goog" (fnil conj []) goog-file)))) {} (apply goog/goog-dep-files require-goog))]
    (merge caches sources foreign-deps goog)))

(patch-planck-js-eval)

(let [{:keys [output-to cljsbuild-out] :as dep-spec} (r/read-string (slurp (get-named-arg "live-deps")))
      _ (swap! extra-paths conj (str user-dir "/" cljsbuild-out))

      bundle-spec (calculate-deps dep-spec)
      deps (bundle-deps bundle-spec)
      js-string (expose-browser-global ".cljs_live_cache" deps)
      out-path (str user-dir "/" output-to)]

  (println "Bundled: \n")
  (pprint bundle-spec)

  (spit out-path js-string)
  (println "emitted " out-path))


#_(let [deps (set/difference transitive-requires provided)]
    (->> deps
         (#(disj % ns-name))
         (reduce
           (fn [m namespace]
             (let [path (ns->path namespace)]
               (cond (foreign-lib? namespace)
                     (assoc m (munge (str path ".js")) (foreign-lib-src namespace))
                     (goog/goog? namespace)
                     (let [goog-files (goog/goog-dep-files namespace)]
                       (reduce (fn [m goog-file]
                                 (let [path (munge goog-file)]
                                   (-> m
                                       (assoc path (resource goog-file))
                                       (update "preload_goog" (fnil conj []) path)))) m goog-files))
                     :else (let [source (resource (str path ".js"))]
                             (cond-> (assoc m (munge (str path ".cache.json")) (cache-str namespace))
                                     source (assoc (munge (str path ".js")) source)))))) {})
         (merge (reduce (fn [m namespace]
                          (let [src (macro-str namespace)
                                path (munge (ns->path namespace "$macros.clj"))]
                            (if-not src m (-> m
                                              (assoc path src)
                                              (update "preload_macros" (fnil conj []) path))))) {} require-macros))
         (merge (reduce (fn [m namespace]
                          (assoc m (munge (ns->path namespace ".cache.json"))
                                   (cache-str namespace))) {} require-cache))))