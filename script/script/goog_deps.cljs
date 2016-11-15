#!/usr/bin/env planck
(ns script.goog-deps
  (:require [planck.core :refer [*command-line-args* slurp]]
            [planck.shell :as shell :refer [sh]]
            [planck.io :as io]
            [clojure.set :refer [difference union intersection]]
            [planck.repl :as repl]
            [clojure.string :as string]
            [script.io :refer [resource]]))

(def dep-cache (-> (:out (sh "node" "../read_closure_library_deps.js"))
                   js/JSON.parse
                   js->clj))

(def dep-graph
  (reduce (fn [s [k deps]]
            (assoc s k (set (keys deps)))) {} (seq (get dep-cache "requires"))))

;https://gist.github.com/alandipert/1263783

;; Copyright (c) Alan Dipert. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.
(defn without
  "Returns set s with x removed."
  [s x] (difference s #{x}))

(defn take-1
  "Returns the pair [element, s'] where s' is set s with element removed."
  [s] {:pre [(not (empty? s))]}
  (let [item (first s)]
    [item (without s item)]))

(defn no-incoming
  "Returns the set of nodes in graph g for which there are no incoming
  edges, where g is a map of nodes to sets of nodes."
  [g]
  (let [nodes (set (keys g))
        have-incoming (apply union (vals g))]
    (difference nodes have-incoming)))

(defn normalize
  "Returns g with empty outgoing edges added for nodes with incoming
  edges only.  Example: {:a #{:b}} => {:a #{:b}, :b #{}}"
  [g]
  (let [have-incoming (apply union (vals g))]
    (reduce #(if (get % %2) % (assoc % %2 #{})) g have-incoming)))

(defn kahn-sort
  "Proposes a topological sort for directed graph g using Kahn's
   algorithm, where g is a map of nodes to sets of nodes. If g is
   cyclic, returns nil."
  ([g]
   (kahn-sort (normalize g) [] (no-incoming g)))
  ([g l s]
   (if (empty? s)
     (when (every? empty? (vals g)) l)
     (let [[n s'] (take-1 s)
           m (g n)
           g' (reduce #(update-in % [n] without %2) g m)]
       (recur g' (conj l n) (union s' (intersection (no-incoming g') m)))))))

; end kahn-sort


(defn goog? [namespace]
  (= "goog" (-> namespace
                str
                (string/split ".")
                first)))

(defn path [namespace]
  (get (repl/closure-index-mem) namespace))

(defn goog-src [namespace]
  (if (#{} namespace)
    ""
    (when-let [path (path namespace)]
      (resource (str path ".js")))))

(defn name-to-path [name]
  (get-in dep-cache ["nameToPath" (str name)]))

(defn immediate-deps [path]
  (->> (get dep-graph path)
       (map name-to-path)))

(defn dep-graph-subset [path]
  (let [imm-deps (immediate-deps path)]
    (merge (select-keys dep-graph (cons path imm-deps))
           (apply merge (map dep-graph-subset imm-deps)))))

(defn goog-dep-files [& goog-deps]
  (->>
    (apply merge (map (comp dep-graph-subset name-to-path) goog-deps))
    (reduce-kv (fn [m k v] (assoc m k (set (map name-to-path v)))) {})
    kahn-sort
    reverse
    (map (partial str "goog/"))
    vec))

;(defn goog-dep-files* [path]
;  (distinct (concat (cons path (immediate-deps path)) (mapcat goog-dep-files* (immediate-deps path)))))
;
;(defn compile [& flags]
;    (let [args (concat ["java" "-jar" "node_modules/google-closure-compiler/compiler.jar"]
;                       (->> (flatten flags)
;                            (partition 2)
;                            (map (partial string/join "="))))]
;      (apply sh args)))
;
;(def closure-lib-dir "node_modules/google-closure-library/closure/")
;
;(defn tempname [] (str "script/" (munge (gensym)) ".txt"))
;
;(defn goog-dep-files-from-compile
;    "Returns the list of files included in a compile of the supplied Closure Library dependencies"
;    [& goog-deps]
;    (let [temp-manifest (tempname)
;          temp-out (tempname)]
;      (compile
;        "--compilation_level" "WHITESPACE_ONLY"             ;; WHITESPACE_ONLY | SIMPLE | ADVANCED
;        "--js" (str closure-lib-dir "**.js")
;        "--warning_level" "VERBOSE"                         ;; QUIET | DEFAULT | VERBOSE
;        "--formatting" "PRETTY_PRINT"                       ;; PRETTY_PRINT, PRINT_INPUT_DELIMITER, SINGLE_QUOTES
;        "--dependency_mode" "STRICT"                        ;; NONE | LOOSE | STRICT
;        "--output_manifest" temp-manifest
;        "--js_output_file" temp-out
;        (for [dep goog-deps]
;          ["--entry_point" dep]))
;      (let [deps (-> (slurp temp-manifest)
;                     (string/replace closure-lib-dir "")
;                     (string/split "\n"))
;            deps (filter #(not= % "goog/base.js") deps)]
;        (doseq [t [temp-out temp-manifest]]
;          (io/delete-file t))
;        ;; ignore goog/base because cljs environments always have this already
;        deps)))
;
;(defn goog-dep-files-with-assert [& goog-deps]
;  (let [deps (apply goog-dep-files goog-deps)
;        compile-deps (vec (apply goog-dep-files-from-compile goog-deps))]
;    (prn :equal? (= deps compile-deps)
;         :sets-equal? (= (set deps) (set compile-deps)))
;    deps))