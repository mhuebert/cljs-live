(ns script.build
  (:require [cljs.build.api]))

(prn "Starting compile...")

(def compile-dir "resources/public/js/compiled/")

(let [build-fn (if (= "--watch" (first *command-line-args*))
                 cljs.build.api/watch
                 cljs.build.api/build)]
  (time (build-fn "src"
                  {:main           "cljs-live.examples"
                   :output-to      (str compile-dir "cljs_live.js")
                   :output-dir     (str compile-dir "out")
                   :cache-analysis true
                   ;:pseudo-names   true
                   :optimizations  :none
                   :asset-path     "js/compiled/out"
                   :dump-core      false
                   ;:source-map     (str compile-dir "cljs_live.js.map")
                   :parallel-build true})))
