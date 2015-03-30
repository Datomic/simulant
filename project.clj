(defproject com.datomic/simulant "0.1.7-SNAPSHOT"
  :description      "Simulation testing with Datomic"
  :url              "https://github.com/Datomic/simulant"
  :license          {:name "Eclipse Public License - v 1.0"
                     :url "http://www.eclipse.org/legal/epl-v10.html"
                     :distribution :repo
                     :comments "same as Clojure"}
  :source-paths     ["src" "examples"]
  :resource-paths   ["resources"]
  :plugins          [[lein-marginalia "0.7.1"]]
  :min-lein-version "2.0.0"
  :jvm-opts         ["-Xmx2g" "-Xms2g" "-server" "-Ddatomic.objectCacheMax=128m"
                     "-Ddatomic.memoryIndexMax=256m" "-Ddatomic.memoryIndexThreshold=32m"]
  :dependencies     [[org.clojure/clojure "1.5.1"]
                     [org.clojure/test.generative "0.5.0"]
                     [com.datomic/datomic-free "0.9.4815.12"
                      :exclusions [org.clojure/clojure]]])
