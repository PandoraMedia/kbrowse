/**
 * Copyright 2017-present Pandora Media, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

(function() {
  const ERROR = '{"error":';
  const PIONEER = '[{"type":"pioneer"}';
  let dataParseFromIndex = PIONEER.length;
  let numLoadingDots = 0;
  let loadingTimer = null;
  let numResults = 0;
  let xhr = null;
  // Keep a local copy for faster topics updating when user changes bootstrap
  // servers dropdown.
  let bootstrapTopics = null;
  const partitionHelpText = ''
    + 'If selected, this will optimize your search by only subscribing to the '
    + '<b>partition</b> corresponding to the key you\'ve specified.';
  const keyHelpText = 'A regex to filter <b>key</b>s on';
  const valueHelpText = 'A regex to filter <b>value</b>s on';
  const offsetHelpText = ''
    + 'The <b>relative offset</b> to start searching from.  Positive values '
    + 'are relative to the earliest message in a topic.  Negative values are '
    + 'relateive to the last message in a topic.';
  const followHelpText = 'Should KBrowse continually update and <b>follow</b> '
    + 'the topic?';
  const partitionCSVHelpText = 'A comma separated list of <b>partitions</b> '
    + 'to follow.';

  window.onload = function() {
    loadServerConfigs(function() {
      populateFromUri();
      hideLoading();
      initDialog();

      // Save state in URL on every change.
      // Add onchange events here at the top, to allow overriding.
      let elements = document.getElementById('query-form').elements;
      for (let i = 0; i < elements.length; i++) {
        elements[i].onchange = updateDataURL;
      }

      document.getElementById('bootstrap-servers').onchange = function() {
        updateDataURL();
        let servers = document.getElementById('bootstrap-servers').value;
        updateTopics(bootstrapTopics[servers],
               document.getElementById('topic').value);
      };
      document.getElementById('query-form').onsubmit = function(e) {
        e.preventDefault();
      };
      document.getElementById('default-partition-lookup').onclick =
        defaultPartitionLookup;
      document.getElementById('submit').onclick = submitQuery;
      document.getElementById('cancel').onclick = cancelQuery;
      document.getElementById('curl').onclick = showCurl;
      bindHelp('help-partition', partitionHelpText);
      bindHelp('help-key', keyHelpText);
      bindHelp('help-value', valueHelpText);
      bindHelp('help-offset', offsetHelpText);
      bindHelp('help-follow', followHelpText);
      bindHelp('help-partition-csv', partitionCSVHelpText);

      loadCached();
    });
  };


  /**
   * Load server-side configs to initialize dropdown inputs.
   * @param {string} onSuccess
   */
  function loadServerConfigs(onSuccess) {
    let path = 'server-configs';
    let currentServers = document.getElementById('bootstrap-servers').value;
    if (currentServers != null) {
      path += '?bootstrap-servers=' + currentServers;
    }
    fetch(path)
    .then(function(response) {
      return response.json();
    })
    .then(function(data) {
      let keys = Object.keys(data);
      for (let i = 0; i < keys.length; i++) {
        let key = keys[i];
        let value = data[key];
        switch (key) {
          case 'bootstrap-servers':
            initBootstrapServers(value);
            break;
          case 'value-deserializers':
            initValueDeserializers(value);
            break;
          case 'schema-registry-urls':
            initSchemaRegistryURLs(value);
            break;
          case 'bootstrap-topics':
            bootstrapTopics = value;
            break;
        }
      }
      onSuccess();
    });
  }

  /**
   * Initialize bootstrap servers dropdown.
   * @param {object} bootstrapServers
   */
  function initBootstrapServers(bootstrapServers) {
    Object.keys(bootstrapServers).forEach(function(key) {
      let option = document.createElement('option');
      option.text = key;
      option.value = bootstrapServers[key];
      document.getElementById('bootstrap-servers').add(option);
    });
  }

  /**
   * Initialize value deserializers dropdown.
   * @param {object} valueDeserializers
   */
  function initValueDeserializers(valueDeserializers) {
    Object.keys(valueDeserializers).forEach(function(key) {
      let option = document.createElement('option');
      option.text = key;
      option.value = valueDeserializers[key];
      document.getElementById('value-deserializer').append(option);
    });
  }

  /**
   * Initialize schema registry urls dropdown.
   * @param {object} schemaRegistryURLs
   */
  function initSchemaRegistryURLs(schemaRegistryURLs) {
    if (schemaRegistryURLs) {
      Object.keys(schemaRegistryURLs).forEach(function(key) {
        let option = document.createElement('option');
        option.text = key;
        option.value = schemaRegistryURLs[key];
        document.getElementById('schema-registry-url').append(option);
      });
      document.getElementById('schema-registry-url-label').style.opacity = 1.0;
      document.getElementById('schema-registry-url').disabled = false;
    } else {
      document.getElementById('schema-registry-url-label').style.opacity = 0.5;
      document.getElementById('schema-registry-url').disabled = true;
    }
  }

  /**
   * Update topics dropdown.
   * @param {object} topics
   * @param {string} selectedTopic
   */
  function updateTopics(topics, selectedTopic) {
    // Sort alphabetical
    topics = topics.sort();

    // Try to keep previous topic selected, even if bootstrap servers have
    // changed.
    if (selectedTopic == null && topics.length > 0) {
      selectedTopic = topics[0];
    }

    let topic = document.getElementById('topic');
    while (topic.length > 0) {
      topic.removeChild(topic.childNodes[0]);
    }

    topics.sort().forEach(function(topic) {
      let option = document.createElement('option');
      option.text = topic;
      option.value = topic;
      if (topic == selectedTopic) {
        option.selected = true;
      }
      document.getElementById('topic').append(option);
    });
  }

  /** Support the sharing of results by copy/pasting the URL. */
  function loadCached() {
    fetch(getPath('cached'))
    .then(function(response) {
      return response.text();
    })
    .then(function(text) {
      processJsonResults(text);
    });
  }

  /** Look up the default partition for the current key. */
  function defaultPartitionLookup() {
    let dataURL = getDataURL();
    let key = dataURL.key;
    if (!key) {
      showDialog('Please enter a key first.');
      return;
    }
    let data = {
      'bootstrap-servers': dataURL.bootstrapServers,
      // This is just a single topic
      'topic': dataURL.topic,
    };
    if (key) {
      data['key'] = key;
    }
    let path = '/default-partition?' + Object.keys(data).map(
      function(k) {
 return k + '=' + data[k];
}
    ).join('&');
    fetch(path)
    .then(function(response) {
 return response.json();
})
    .then(function(data) {
      showDialog('default partition for "' + key + '": ' + data);
    });
  }

  /** Initialize the dialog elements. */
  function initDialog() {
    document.getElementById('dialog-bg').onclick = function(event) {
      document.getElementById('dialog-fg').style.display = 'none';
      document.getElementById('dialog-bg').style.display = 'none';
    };
    document.getElementById('dialog-fg').onclick = function(event) {
      event.stopPropagation();
    };
  }

  /**
   * Show a message in the modal dialog.
   * @param {string} message
   */
  function showDialog(message) {
    document.getElementById('dialog-text').innerHTML = message;
    document.getElementById('dialog-bg').style.display = 'block';
    document.getElementById('dialog-fg').style.display = 'block';
  }

  /** Show a curl request for the current UI state. */
  function showCurl() {
    let path = getPath('search');
    let host = window.location.hostname;
    let port = window.location.port;
    let text = 'curl "' + host + ':' + port + path + '"';
    showDialog(text);
  }

  /**
   * Show the loading dialog.
   */
  function showLoading() {
    document.getElementById('loading').style.display = 'block';
    numResults = 0;
    if (loadingTimer == null) {
      loadingTimer = window.setInterval(function() {
        numLoadingDots += 1;
        numLoadingDots = numLoadingDots % 4;
        loadingText = 'Loading';
        for (let i = 0; i < numLoadingDots; i++) {
          loadingText += '.';
        }
        document.getElementById('loading-text').text = loadingText;
      }, 500);
    }
  }

  /**
   * Hide the loading dialog.
   */
  function hideLoading() {
    document.getElementById('loading').style.display = 'none';
    numResults = 0;
    document.getElementById('loading-partition').innerHTML = '';
    document.getElementById('loading-offset').innerHTML = '';
    document.getElementById('loading-timestamp').innerHTML = '';
    document.getElementById('loading-num-results').innerHTML = '';
    clearInterval(loadingTimer);
    loadingTimer = null;
  }

  /** Set the UI state from the it's serialized value in the window location.
   */
  function populateFromUri() {
    let search = decodeURI(window.location.search);
    let selectedTopic = null;
    if (search.length > 1) {
      let dataURL = JSON.parse(decodeURI(search.substr(1)));
      document.getElementById('key').value = dataURL.key;
      document.getElementById('val-regex').value = dataURL.valRegex;
      document.getElementById('relative-offset').value = dataURL.relativeOffset;
      document.getElementById('follow').checked = dataURL.follow;
      document.getElementById('default-partition').checked =
        dataURL.defaultPartition;
      document.getElementById('partitions').value = dataURL.partitions;

      let bootstrapServers = document.getElementById('bootstrap-servers');
      for (let i = 0; i < bootstrapServers.length; i++) {
        let element = bootstrapServers[i];
        if (element.value == dataURL.bootstrapServers) {
          document.getElementById('bootstrap-servers').selectedIndex = i;
          break;
        }
      };

      let valueDeserializer = document.getElementById('value-deserializer');
      for (let i = 0; i < valueDeserializer.length; i++) {
        let element = valueDeserializer[i];
        if (element.value == dataURL.valueDeserializer) {
          document.getElementById('value-deserializer').selectedIndex = i;
          break;
        }
      };

      let schemaRegistryURL = document.getElementById('schema-registry-url');
      for (let i = 0; i < schemaRegistryURL.length; i++) {
        let element = schemaRegistryURL[i];
        if (element.value == dataURL.schemaRegistryURL) {
          document.getElementById('schema-registry-url').selectedIndex = i;
          break;
        }
      };

      selectedTopic = dataURL.topic;
    }

    // Update topics, regardless of whether window location has data
    let bootstrapServers = document.getElementById('bootstrap-servers').value;
    updateTopics(bootstrapTopics[bootstrapServers], selectedTopic);
  }

  /**
   * Process a complete json result.
   * @param {string} jsonStr
   */
  function processJson(jsonStr) {
    try {
      let object = JSON.parse(jsonStr);
      if (object.type == 'result') {
        let hr = document.createElement('hr');
        let result = document.createElement('pre');
        result.innerText = JSON.stringify(object, null, 4);
        document.getElementById('results').prepend(hr);
        document.getElementById('results').prepend(result);
        numResults++;
      }
      document.getElementById('loading-partition').innerHTML = object.partition;
      document.getElementById('loading-offset').innerHTML = object.offset;
      document.getElementById('loading-timestamp').innerHTML = object.timestamp;
      document.getElementById('loading-num-results').innerHTML = numResults;
    } catch (err) {
      console.log('json parse failure for: ' + jsonStr);
    }
  }

  /**
   * Process a chunk of streaming json data objects.
   * @param {string} data
   */
  function processJsonResults(data) {
    // Attempt to pretty print if it looks like JSON
    let braceDepth = 0;
    for (let i = dataParseFromIndex; i < data.length; i++) {
      let c = data.charAt(i);
      if (c == '{') {
        if (braceDepth == 0) {
          // Skip commas/whitespace prior to start of object
          dataParseFromIndex = i;
        }
        braceDepth++;
      } else if (c == '}') {
        braceDepth--;
        if (braceDepth == 0) {
          // Appears to be complete JSON object
          processJson(data.substring(dataParseFromIndex, i + 1));
          dataParseFromIndex = i + 1;
        }
      }
    }
  }

  /** Encode the UI state for storage in the window location.
   * @return {object}
   */
  function getDataURL() {
    return {
      key: document.getElementById('key').value,
      valRegex: document.getElementById('val-regex').value,
      bootstrapServers: document.getElementById('bootstrap-servers').value,
      // API accepts CSV of multiple topics, but we just provide
      // a single topic as a dropdown UI, so just pass that.
      topic: document.getElementById('topic').value,
      relativeOffset: document.getElementById('relative-offset').value,
      follow: document.getElementById('follow').checked,
      defaultPartition: document.getElementById('default-partition').checked,
      valueDeserializer: document.getElementById('value-deserializer').value,
      schemaRegistryURL: document.getElementById('schema-registry-url').value,
      partitions: document.getElementById('partitions').value,
    };
  }

  /** Create query arg path for querying server.
   * @param {string} endpoint
   * @return {string}
   */
  function getPath(endpoint) {
    let dataURL = getDataURL();
    let data = {
      'bootstrap-servers': dataURL.bootstrapServers,
      'topics': dataURL.topic,
      'print-offset': 10000,
    };
    if (dataURL.key) {
      data['key-regex'] = dataURL.key;
    }
    if (dataURL.valRegex) {
      // The thought here is that users are more likely to put values
      // in the value field that they intend to match anywhere,
      // whereas the key-regex is more likely to be an exact match.
      data['val-regex'] = '.*' + dataURL.valRegex + '.*';
    }
    if (dataURL.valueDeserializer) {
      data['value-deserializer'] = dataURL.valueDeserializer;
    }
    if (dataURL.schemaRegistryURL) {
      data['schema-registry-url'] = dataURL.schemaRegistryURL;
    }
    if (dataURL.relativeOffset) {
      data['relative-offset'] = dataURL.relativeOffset;
    }
    if (dataURL.follow) {
      data['follow'] = dataURL.follow;
    }
    if (dataURL.defaultPartition) {
      data['default-partition'] = dataURL.defaultPartition;
    }
    if (dataURL.partitions) {
      data['partitions'] = dataURL.partitions;
    }
    let path = '/' + endpoint + '?' + Object.keys(data).map(
      function(k) {
 return k + '=' + data[k];
}
    ).join('&');
    return encodeURI(path, 'UTF-8');
  }

  /**
   * Store the UI state as serialized JSON in the window's location.
   * This makes it easier for users to share the context of their
   * queries/results.
   */
  function updateDataURL() {
    window.history.replaceState(
      null,
      null,
      '?' + JSON.stringify(getDataURL())
    );
  }

  /** Send a query to the server. */
  function submitQuery() {
    document.getElementById('error').style.display = 'none';

    updateDataURL();
    dataParseFromIndex = PIONEER.length;

    xhr = new XMLHttpRequest();
    let path = getPath('search');
    xhr.open('GET', path, true);
    xhr.onprogress = function() {
      if (xhr.responseText.startsWith(ERROR)) {
        hideLoading();
        document.getElementById('error').text = xhr.responseText;
        document.getElementById('error').style.display = 'block';
      } else if (xhr.responseText.startsWith(PIONEER)) {
        processJsonResults(xhr.responseText);
      } else {
        document.getElementById('results').prepend(xhr.responseText);
      }
    };
    xhr.onreadystatechange = function() {
      if (xhr.readyState == 4) {
        hideLoading();
      }
    };

    // Clear old results
    let results = document.getElementById('results');
    while (results.childNodes.length > 0) {
      results.removeChild(results.childNodes[0]);
    }

    xhr.send();
    showLoading();
  }

  /** Cancel an in-progress query. */
  function cancelQuery() {
    xhr.abort();
    hideLoading();
  }

  /** Set up help `text` for `id` */
  /**
   * Set up help text for the given id.
   * @param {string} id
   * @param {string} text
   */
  function bindHelp(id, text) {
    document.getElementById(id).onclick = function() {
      showDialog(text);
    };
  }
})();
