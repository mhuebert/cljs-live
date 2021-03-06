(defproject cljs-live "0.3.1-SNAPSHOT"
  :description "Tools for bunding dependencies for self-hosted ClojureScript"
  :url "https://github.com/braintripping/cljs-live"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.7.1"

  :dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                 [org.clojure/clojurescript "1.9.946"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [com.cognitect/transit-cljs "0.8.239"]
                 [org.clojure/data.json "0.2.6"]
                 [me.raynes/conch "0.8.0"]
                 [thheller/shadow-cljs "2.0.8"]]

  :lein-release {:deploy-via :clojars}

  :clean-targets ^{:protect false} ["resources/public/js/compiled/out" ".planck_cache"]

  :source-paths ["src" "script"])



