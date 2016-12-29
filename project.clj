(defproject badilanti "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.89" :scope "provided"]
                 [org.clojure/core.async "0.2.391"]
                 [com.cognitect/transit-clj "0.8.285"]
                 [ring "1.4.0"]
                 [ring/ring-defaults "0.2.0"]
                 [bk/ring-gzip "0.1.1"]
                 [ring.middleware.logger "0.5.0"]
                 [ring-middleware-format "0.7.0"]
                 [compojure "1.5.0"]
                 [environ "1.0.3"]
                 ;; you can chose either http-kit or jetty (see
                 ;; server.clj for more conditional inclusions)
                 #_[ring/ring-jetty-adapter "1.5.0"]
                 [http-kit "2.1.19"]
                 [reagent "0.6.0-rc"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.2"]
                 [cljs-http "0.1.41"]
                 [com.andrewmcveigh/cljs-time "0.4.0"]
                 [cljsjs/react-bootstrap "0.30.2-0"
                  ;; Use Reagent's own version (newer) instead.
                  :exclusions [cljsjs/react]]
                 [cljsjs/fixed-data-table "0.6.3-0"
                  ;; Use Reagent's own version (newer) instead.
                  :exclusions [cljsjs/react]]
                 [io.forward/clojure-mail "1.0.5"]
                 [hiccup "1.0.5"]
                 [clj-soup/clojure-soup "0.1.3"]
                 [clojurewerkz/elastisch "2.2.2"]
                 [buddy/buddy-auth "1.3.0"]
                 [buddy/buddy-hashers "1.1.0"]
                 ;; REMOVE: just use buddy -wcp6/12/16.
                 [com.cemerick/friend "0.2.3"
                  :exclusions [org.clojure/core.cache]]
                 #_[re-com "1.1.0"
                  ;; Use Reagent version above
                  :exclusions [reagent/reagent
                               cljsjs/react]]
                 #_[com.novemberain/monger "3.1.0"]
                 [org.clojure/core.cache "0.6.4"]
                 [pathetic "0.5.1"]]

  :plugins [[lein-cljsbuild "1.1.3"]
            [lein-environ "1.0.3"]
            [lein-externs "0.1.5"]
            [refactor-nrepl "2.3.0-SNAPSHOT"]]

  :min-lein-version "2.6.1"

  :source-paths ["src/clj" "src/cljs" "src/cljc"]

  :test-paths ["test/clj" "test/cljc"]

  :clean-targets ^{:protect false} [:target-path :compile-path "resources/public/js"]

  :uberjar-name "badilanti.jar"

  ;; Use `lein run` if you just want to start a HTTP server, without figwheel
  :main badilanti.server

  ;; nREPL by default starts in the :main namespace, we want to start in `user`
  ;; because that's where our development helper functions like (run) and
  ;; (browser-repl) live.
  :repl-options {:init-ns user}

  :cljsbuild {:builds
              [{:id "app"
                :source-paths ["src/cljs" "src/cljc"]

                :figwheel true
                ;; Alternatively, you can configure a function to run every time figwheel reloads.
                ;; :figwheel {:on-jsload "badilanti.core/on-figwheel-reload"}

                :compiler {:main badilanti.core
                           :asset-path "js/compiled/out"
                           :output-to "resources/public/js/compiled/badilanti.js"
                           :output-dir "resources/public/js/compiled/out"
                           :source-map-timestamp true}}

               {:id "test"
                :source-paths ["src/cljs" "test/cljs" "src/cljc" "test/cljc"]
                :compiler {:output-to "resources/public/js/compiled/testable.js"
                           :main badilanti.test-runner
                           :optimizations :none}}

               {:id "min"
                :source-paths ["src/cljs" "src/cljc"]
                :jar true
                :compiler {:main badilanti.core
                           :output-to "resources/public/js/compiled/badilanti.js"
                           :output-dir "target"
                           :source-map-timestamp true
                           :optimizations :advanced
                           :pretty-print false}}]}

  ;; When running figwheel from nREPL, figwheel will read this configuration
  ;; stanza, but it will read it without passing through leiningen's profile
  ;; merging. So don't put a :figwheel section under the :dev profile, it will
  ;; not be picked up, instead configure figwheel here on the top level.

  :figwheel {;; :http-server-root "public"       ;; serve static assets from resources/public/
             ;; :server-port 3449                ;; default
             ;; :server-ip "127.0.0.1"           ;; default
             :css-dirs ["resources/public/css"]  ;; watch and update CSS

             ;; Instead of booting a separate server on its own port, we embed
             ;; the server ring handler inside figwheel's http-kit server, so
             ;; assets and API endpoints can all be accessed on the same host
             ;; and port. If you prefer a separate server process then take this
             ;; out and start the server with `lein run`.
             :ring-handler user/http-handler

             ;; Start an nREPL server into the running figwheel process. We
             ;; don't do this, instead we do the opposite, running figwheel from
             ;; an nREPL process, see
             ;; https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl
             ;; :nrepl-port 7888

             ;; To be able to open files in your editor from the heads up display
             ;; you will need to put a script on your path.
             ;; that script will have to take a file path and a line number
             ;; ie. in  ~/bin/myfile-opener
             ;; #! /bin/sh
             ;; emacsclient -n +$2 $1
             ;;
             ;; :open-file-command "myfile-opener"

             :server-logfile "log/figwheel.log"}

  :doo {:build "test"}

  :profiles {:dev
             {:dependencies [[figwheel "0.5.4-4"]
                             [figwheel-sidecar "0.5.4-4"]
                             [com.cemerick/piggieback "0.2.1"]
                             [org.clojure/tools.nrepl "0.2.12"]]

              :plugins [[lein-figwheel "0.5.4-4"]
                        [lein-doo "0.1.6"]]

              :source-paths ["dev"]
              :repl-options {:init (do
                                     (set! *print-length* 20)
                                     (set! *print-level* 5))
                             :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}

             :uberjar
             {:source-paths ^:replace ["src/clj" "src/cljc"]
              :prep-tasks ["compile" ["cljsbuild" "once" "min"]]
              :hooks []
              :omit-source true
              :aot :all}})
