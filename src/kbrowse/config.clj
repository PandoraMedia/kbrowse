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

(ns kbrowse.config
  "Enviroment settings
   This contains defaults for Kafka behavior, and server-specific settings,
   such as a map of Kafka clusters, for populating the console dropdowns.
   All settings are loaded from a YAML at the CONFIG path.
   Invidividual values can be overridden with environment variables."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as string]))

(def settings
  (let [file (or (System/getenv "CONFIG") "config/default.yml")]
    (yaml/parse-string (slurp file))))

(defn split-key-value
  "Destructure a key=value string."
  [kv]
  (clojure.string/split kv #"="))

(defn get-map
  "Parse a map from a single environment variable.
   Expected format:
     ENV_VAR='foo=a,bar=b'"
  [env-var default]
  (if-let [value (System/getenv env-var)]
    (->> value
         (#(string/split % #","))
         (map split-key-value)
         (into {}))
    default))

(defn get-int
  [env-var default]
  (if-let [value (System/getenv env-var)]
    (Integer/parseInt value)
    default))

(def kafka-bootstrap-servers
  (get-map "KAFKA_BOOTSTRAP_SERVERS"
           (:kafka-bootstrap-servers settings)))

(def kafka-schema-registry-urls
  (get-map "KAFKA_SCHEMA_REGISTRY_URLS"
           (:kafka-schema-registry-urls settings)))

(def port (get-int "KBROWSE_PORT" (or (:port settings) 4000)))
(def kafka-topics-cache-sleep-seconds (get-int "KAFKA_TOPICS_CACHE_SLEEP_SECONDS" (or (:kafka-topics-cache-sleep-seconds settings) 300)))
(def kafka-timeout (get-int "KAFKA_TIMEOUT" (or (:kafka-timeout settings) 5000)))
(def kafka-seek-to-timestamp-tolerance (get-int "KAFKA_SEEK_TO_TIMESTAMP_TOLERANCE" (or (:kafka-seek-to-timestamp-tolerance settings) 10000)))
(def cache-max-items (get-int "CACHE_MAX_ITEMS" (or (:cache-max-items settings) 100)))
(def cache-ttl-minutes (get-int "CACHE_TTL_MINUTES" (or (:cache-ttl-minutes settings) 60)))
(def cache-item-size-limit (get-int "CACHE_ITEM_SIZE_LIMIT" (or (:cache-item-size-limit settings) 4194304))) ; 4 MB
(def stop-consumers-after-n-seconds (get-int "STOP_CONSUMERS_AFTER_N_SECONDS" (or (:stop-consumers-after-n-seconds settings) 86400)))
