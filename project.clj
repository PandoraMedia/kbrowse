;;;
;;; Copyright 2017-present Pandora Media, Inc.
;;;
;;; Licensed under the Apache License, Version 2.0 (the "License");
;;; you may not use this file except in compliance with the License.
;;; You may obtain a copy of the License at
;;;
;;;  http://www.apache.org/licenses/LICENSE-2.0
;;;
;;; Unless required by applicable law or agreed to in writing, software
;;; distributed under the License is distributed on an "AS IS" BASIS,
;;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;; See the License for the specific language governing permissions and
;;; limitations under the License.
;;;

(defproject com.pandora/kbrowse "0.0.1-SNAPSHOT"
  :description "Grep-like tool for searching kafka topics with regular expressions"
  :dependencies [[ch.qos.logback/logback-classic "1.1.7"]
                 [cheshire "5.8.0"]
                 [clojure-msgpack "1.2.1"]
                 [clj-yaml "0.4.0"]
                 [com.google.guava/guava "24.1-jre"]
                 [compojure "1.6.0"]
                 [io.confluent/kafka-avro-serializer "4.0.0"]
                 [org.apache.avro/avro "1.8.2"]
                 [org.apache.kafka/kafka_2.11 "2.2.1" :exclusions [log4j org.slf4j/slf4j-log4j12]]
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.logging "0.4.0"]
                 [ring/ring-core "1.6.3" :exclusions [commons-codec]]
                 [ring/ring-jetty-adapter "1.6.3"]]
  :repositories {"central" "https://repo1.maven.org/maven2/"
                 "confluent" "https://packages.confluent.io/maven/"}
  :plugins [[lein-ring "0.12.4" :exclusions [org.clojure/clojure]]
            [lein-cljfmt "0.5.7"]]
  :ring {:handler kbrowse.core/app
         ; Prevent buffering response, which sets "Content-Length"
         ; and removes "Transfer-Encoding": "chunked".
         :adapter {:output-buffer-size 100}}
  :main kbrowse.core
  :profiles {:uberjar {:aot :all}}
  :test-selectors {:default (complement :integration)
                   :integration :integration})
