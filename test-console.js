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


/**
 * This is an integration test for the browser DOM.
 * It assumes a local KBrowse is running, and that a topic named "kbrowse"
 * contains a string-serialized record:
 *     key: k
 *     value: v
 */

const assert = require('assert');
const puppeteer = require('puppeteer');
const threadSleep = require('thread-sleep');

(async _ => {
    try {
        const browser = await puppeteer.launch();

        await runTest(browser, checkIDs);
        await runTest(browser, searchSomeResults);
        await runTest(browser, searchNoResults);

        await browser.close();
    } catch (error) {
        console.log(error);
        process.exit(1);
    }
})();

async function runTest(browser, testPageFunction) {
    const page = await browser.newPage();
    await page.goto('http://localhost:4000', {});

    // Ensure "kbrowse" topic is selected.
    const topic = await page.$('#topic');
    await topic.type('kbrowse');

    await testPageFunction(page);

    await page.close();
}

/**
 * Load elements by ID.
 * This is an attempt to remind us to update the Javascript whenever the
 * HTML ID names changes.
   * @param {page} page
 */
async function checkIDs(page) {
    // Main UI
    assert(await page.$('#key'));
    assert(await page.$('#val-regex'));
    assert(await page.$('#bootstrap-servers'));
    assert(await page.$('#schema-registry-url'));
    assert(await page.$('#topic'));
    assert(await page.$('#default-partition'));
    assert(await page.$('#relative-offset'));
    assert(await page.$('#follow'));
    assert(await page.$('#value-deserializer'));
    assert(await page.$('#partitions'));

    // Help buttons
    assert(await page.$('#help-partition'));
    assert(await page.$('#help-key'));
    assert(await page.$('#help-value'));
    assert(await page.$('#help-offset'));
    assert(await page.$('#help-follow'));
    assert(await page.$('#help-partition-csv'));

    // Loading dialog
    assert(await page.$('#loading-partition'));
    assert(await page.$('#loading-offset'));
    assert(await page.$('#loading-timestamp'));
    assert(await page.$('#loading-num-results'));

    // Modal dialog
    assert(await page.$('#dialog-bg'));
    assert(await page.$('#dialog-fg'));
    assert(await page.$('#dialog-text'));
}

async function searchSomeResults(page) {
    await page.type('#key', 'k');
    await page.click('#submit');
    threadSleep(500);
    const resultsHTML = await page.evaluate(results => results.innerHTML, await page.$('#results'));
    assert(resultsHTML != "");
}

async function searchNoResults(page) {
    await page.type('#key', 'noresults');
    await page.click('#submit');
    threadSleep(500);
    const resultsHTML = await page.evaluate(results => results.innerHTML, await page.$('#results'));
    assert(resultsHTML == "");
}
