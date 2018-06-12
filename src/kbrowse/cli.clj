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

(ns kbrowse.cli
  "CLI options, and parsing logic"
  (:require [clojure.tools.cli :refer [parse-opts]]))

(def cli-options
  "This is a list of clojure-tools cli opts.
   The short opts are left nil, as the large number gets unwieldy.
   The primary expected use-case is the web console, so these args
   are mainly useful as a way of parsing query string args into
   the corresponding long opts."
  [[nil "--bootstrap-servers BOOTSTRAP_SERVERS" "Kafka bootstrap servers (comma-separated)"
    :missing "Missing --bootstrap-servers"]
   [nil "--topics TOPICS" "Kafka topics (comma-separated)"
    :missing "Missing --topics"]
   [nil "--key-deserializer KEY_DESERIALIZER" "Kafka key deserializer - check kafka/deserializers for valid options"
    :default "org.apache.kafka.common.serialization.StringDeserializer"]
   [nil "--value-deserializer VALUE_DESERIALIZER" "Kafka key deserializer - check kafka/deserializers for valid options"
    :default "org.apache.kafka.common.serialization.StringDeserializer"]
   [nil "--schema-registry-url SCHEMA_REGISTRY_URL" "Kafka schema registry url"
    :default nil]
   [nil "--default-partition" "Consumes only only default partition for the given key"
    :default nil]
   [nil "--key-regex KEY_REGEX" "Key regular expression"
    :default nil]
   [nil "--val-regex VAL_REGEX" "Value regular expression"
    :default nil]
   [nil "--partitions PARTITIONS" "Partitions to search (defaults to all)"
    :default nil]
   [nil "--print-offset PRINT_OFFSET" "Print offset every n records"
    :default nil
    :parse-fn #(Long/parseLong %)]
   [nil "--check-crcs CHECK_CRCS" "kafka setting: \"check.crcs\""]
   [nil "--relative-offset RELATIVE_OFFSET" "Relative start offset - if >= 0, will add to earliest - if < 0, will subtract from latest"
    :default nil
    :parse-fn #(Long/parseLong %)]
   [nil "--follow" "Continue consuming the stream as new records come in"]
   ["-h" "--help"]])

(defn parse
  [args]
  (let [{:keys [options arguments errors summary] :as parsed} (parse-opts args cli-options)]
    (when-not (:help options)
      (if (seq errors)
        (throw (IllegalArgumentException. (.toString errors))))
      (if (and (:default-partition options) (not (:key-regex options)))
        (throw (IllegalArgumentException. "--default-partition requires --key-regex")))
      (if (and (:default-partition options) (:partitions options))
        (throw (IllegalArgumentException. "--default-partition / --partitions not intended for use together")))
      (if (and (:start-timestamp options) (:relative-offset options))
        (throw (IllegalArgumentException. "--start-timestamp / --relative-offset not intended for use together"))))
    parsed))
