"use strict";

// Invoked only by browser-tests.py against its disposable, guarded test fixture.
// No existing browser profile, cookies, storage, trace, or tokens are persisted.
const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const path = require("node:path");
const { chromium } = require("playwright");

const suppliedUrl = process.env.CAMPUSLIFE_BROWSER_URL || "";
if (process.env.CAMPUSLIFE_BROWSER_FIXTURE !== "dedicated-test"
    || !/^http:\/\/127\.0\.0\.1:\d{1,5}\/?$/.test(suppliedUrl)) {
  console.error("Refusing to run: use scripts/browser-tests.py and its dedicated loopback test fixture.");
  process.exit(2);
}
const base = new URL(suppliedUrl);
if (!base.port || Number(base.port) < 1024 || Number(base.port) > 65535) {
  console.error("Refusing to run: the fixture must use a dedicated non-privileged loopback port.");
  process.exit(2);
}
const origin = base.origin;
const output = path.resolve(__dirname, "../target/browser-tests");
const report = { generatedAt: new Date().toISOString(), fixture: "dedicated-test",
  browserVersion: null, passed: false, scenarios: [] };
let browser;
let currentPage;
let currentStep = "initialization";
let failure = false;
const contexts = [];
const externalRequests = [];

function step(name) { currentStep = name; }
async function scenario(name, run) {
  const started = Date.now();
  step("starting scenario");
  try {
    await run();
    report.scenarios.push({ name, passed: true, durationMs: Date.now() - started });
    console.log("PASS " + name);
  } catch (error) {
    report.scenarios.push({ name, passed: false, step: currentStep,
      errorType: error.name === "TimeoutError" ? "TimeoutError" : "AssertionOrRequestError",
      durationMs: Date.now() - started });
    // Playwright errors can quote input values: deliberately omit message/stack.
    console.error("FAIL " + name + " (" + currentStep + ")");
    throw error;
  }
}

function isRequest(response, pathname, method = "GET") {
  const url = new URL(response.url());
  return url.origin === origin && url.pathname === pathname && response.request().method() === method;
}
async function act(page, pathname, method, action, expectedStatus = 200) {
  const [response] = await Promise.all([
    page.waitForResponse((result) => isRequest(result, pathname, method)), action()
  ]);
  assert.equal(response.status(), expectedStatus);
  await response.finished();
  return response;
}
async function visibleText(page, selector, text) {
  await page.waitForFunction(({ selector, text }) => {
    const element = document.querySelector(selector);
    return element && element.getClientRects().length > 0 && element.textContent.includes(text);
  }, { selector, text });
}
async function count(page, selector, expected) {
  await page.waitForFunction(({ selector, expected }) => document.querySelectorAll(selector).length === expected,
    { selector, expected });
}
async function newPage() {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 },
    locale: "zh-CN", timezoneId: "Asia/Shanghai", serviceWorkers: "block" });
  contexts.push(context);
  await context.route("**/*", async (route) => {
    if (new URL(route.request().url()).origin !== origin) {
      externalRequests.push("blocked");
      await route.abort("blockedbyclient");
    } else await route.continue();
  });
  const page = await context.newPage();
  page.setDefaultTimeout(12000);
  page.setDefaultNavigationTimeout(15000);
  currentPage = page;
  await page.goto(origin + "/", { waitUntil: "domcontentloaded" });
  await count(page, "#shop-list .shop-card", 3);
  return page;
}
async function login(page, phone) {
  currentPage = page;
  await page.locator("#login-open").click();
  await page.locator("#login-phone").selectOption(phone);
  await act(page, "/api/auth/code", "POST", () => page.locator("#code-button").click());
  await page.waitForFunction(() => /^\d{6}$/.test(document.querySelector("#login-code").value));
  await act(page, "/api/auth/login", "POST", () => page.locator("#login-submit").click());
  await page.locator("#login-dialog").waitFor({ state: "hidden" });
  await page.locator("#logout-button").waitFor({ state: "visible" });
}
async function logout(page) {
  currentPage = page;
  await act(page, "/api/auth/logout", "POST", () => page.locator("#logout-button").click());
  await page.locator("#login-open").waitFor({ state: "visible" });
  await page.locator("#merchant-tab").waitFor({ state: "hidden" });
}
async function openCoffee(page) {
  await page.locator("#discover-tab").click();
  await page.locator("#shop-list .shop-card").filter({ hasText: "课间咖啡" }).getByRole("button").click();
  await visibleText(page, "#detail-title", "课间咖啡");
  await page.locator("#offer-list .offer-card").first().waitFor({ state: "visible" });
}
async function openWallet(page) {
  await act(page, "/api/me/vouchers", "GET", () => page.locator("#wallet-tab").click());
  await page.locator("#wallet-list .owned-voucher").first().waitFor({ state: "visible" });
}
async function openWorkbench(page) {
  await act(page, "/api/merchant/shops", "GET", () => page.locator("#merchant-tab").click());
  await count(page, "#merchant-list .managed-shop", 2);
}
async function redeem(page, code, status) {
  await page.locator("#redemption-code").fill(code);
  return act(page, "/api/merchant/vouchers/redemptions", "POST",
    () => page.locator("#redemption-submit").click(), status);
}
async function safeFailureScreenshot() {
  if (!currentPage || currentPage.isClosed()) return;
  try {
    // Mask whole private surfaces, including any code quoted in an error message.
    await currentPage.screenshot({ path: path.join(output, "failure-masked.png"), fullPage: true,
      mask: [currentPage.locator("#login-dialog"), currentPage.locator("#wallet-view"),
        currentPage.locator("#redemption-form"), currentPage.locator("#redemption-error"),
        currentPage.locator("#redemption-result"), currentPage.locator("#notice"),
        currentPage.locator("#detail-notice"), currentPage.locator("input")] });
  } catch { /* Preserve the sanitized JSON result even if the page crashed. */ }
}

(async () => {
  await fs.mkdir(output, { recursive: true });
  try {
    step("launch isolated Chromium");
    browser = await chromium.launch({ headless: true,
      ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
    report.browserVersion = browser.version();
    step("load seeded public homepage");
    const student = await newPage();
    let coffeeCode;
    let studentOrderId;

    await scenario("Public shop filters include the 18.00 boundary and exclude 17.99", async () => {
      step("capture clean homepage");
      await student.screenshot({ path: path.join(output, "discover.png"), fullPage: true });
      step("filter at exact price");
      await student.locator("#campus-filter").selectOption("东校区");
      await student.locator("#category-filter").selectOption("咖啡");
      await student.locator("#price-filter").fill("18");
      await act(student, "/api/shops", "GET", () => student.locator("#filter-button").click());
      await count(student, "#shop-list .shop-card", 1);
      await visibleText(student, "#shop-list", "课间咖啡");
      step("filter below exact price");
      await student.locator("#price-filter").fill("17.99");
      await act(student, "/api/shops", "GET", () => student.locator("#filter-button").click());
      await visibleText(student, "#shop-list", "暂时没有符合条件的店铺");
      await count(student, "#shop-list .shop-card", 0);
      await act(student, "/api/shops", "GET", () => student.locator("#filter-reset").click());
      await count(student, "#shop-list .shop-card", 3);
    });

    await scenario("Student signs in, claims a voucher and sees it in the private wallet", async () => {
      step("student login through mock-code form");
      await login(student, "13800000001");
      await student.locator("#merchant-tab").waitFor({ state: "hidden" });
      step("claim coffee voucher through page");
      await openCoffee(student);
      await act(student, "/api/vouchers/1/claims", "POST",
        () => student.locator("#offer-list .offer-card").first().getByRole("button").click());
      await visibleText(student, "#detail-notice", "优惠券已收好");
      await student.locator("#shop-close").click();
      await openWallet(student);
      await count(student, "#wallet-list .owned-voucher", 1);
      const voucher = student.locator("#wallet-list .owned-voucher").first();
      coffeeCode = await voucher.locator("code").textContent();
      assert.match(coffeeCode, /^[a-f0-9]{32}$/);
      studentOrderId = await voucher.getAttribute("data-voucher-order-id");
      assert.ok(studentOrderId && studentOrderId !== "undefined");
      await visibleText(student, "#wallet-list .voucher-status", "已领取");
    });

    await scenario("Duplicate claim returns 409 and the same claim survives logout and login", async () => {
      step("duplicate claim rejected");
      await openCoffee(student);
      const response = await act(student, "/api/vouchers/1/claims", "POST",
        () => student.locator("#offer-list .offer-card").first().getByRole("button").click(), 409);
      assert.equal((await response.json()).code, "ALREADY_CLAIMED");
      await visibleText(student, "#detail-notice", "已领取");
      await student.locator("#shop-close").click();
      step("logout clears private wallet");
      await logout(student);
      await count(student, "#wallet-list .owned-voucher", 0);
      step("login restores server-side history");
      await login(student, "13800000001");
      await openWallet(student);
      assert.equal(await student.locator("#wallet-list .owned-voucher").first().getAttribute("data-voucher-order-id"), studentOrderId);
      assert.equal(await student.locator("#wallet-list code").first().textContent(), coffeeCode);
    });

    const west = await newPage();
    await scenario("Merchant lists owned shops including offline shops and can restore visibility", async () => {
      step("west merchant signs in");
      await login(west, "13900000002");
      await openWorkbench(west);
      assert.deepEqual(await west.locator("#merchant-list .managed-shop").evaluateAll((cards) => cards.map((card) => card.dataset.shopId)), ["2", "4"]);
      await visibleText(west, '#merchant-list [data-shop-id="4"]', "已下线");
      await west.locator("#notice-close").click();
      await west.screenshot({ path: path.join(output, "merchant.png"), fullPage: true });
      step("read offline shop through management API");
      await act(west, "/api/merchant/shops/4", "GET",
        () => west.locator('#merchant-list [data-shop-id="4"] button').click());
      await west.locator("#merchant-form").waitFor({ state: "visible" });
      assert.equal(await west.locator("#merchant-status").inputValue(), "0");
      step("bring shop online");
      await west.locator("#merchant-status").selectOption("1");
      await act(west, "/api/shops/4", "PATCH", () => west.locator("#merchant-save").click());
      await visibleText(west, "#merchant-success", "已上线");
      await count(west, "#shop-list .shop-card", 4);
      await west.locator("#merchant-close").click();
      await west.locator("#discover-tab").click();
      await visibleText(west, "#shop-list", "周末球场");
      step("restore original offline status");
      await openWorkbench(west);
      await act(west, "/api/merchant/shops/4", "GET",
        () => west.locator('#merchant-list [data-shop-id="4"] button').click());
      await west.locator("#merchant-form").waitFor({ state: "visible" });
      await west.locator("#merchant-status").selectOption("0");
      await act(west, "/api/shops/4", "PATCH", () => west.locator("#merchant-save").click());
      await visibleText(west, "#merchant-success", "店铺已下线");
      await count(west, "#shop-list .shop-card", 3);
      await west.locator("#merchant-close").click();
      await visibleText(west, '#merchant-list [data-shop-id="4"]', "已下线");
    });

    await scenario("Other merchants cannot open another shop's management form", async () => {
      currentPage = west;
      step("open another merchant's public shop");
      await openCoffee(west);
      const response = await act(west, "/api/merchant/shops/1", "GET",
        () => west.locator("#detail-manage").click(), 403);
      assert.notEqual((await response.json()).code, "OK");
      await west.locator("#merchant-error").waitFor({ state: "visible" });
      assert.equal(await west.locator("#merchant-form").isVisible(), false);
      await west.locator("#merchant-close").click();
    });

    await scenario("A merchant cannot redeem another shop's voucher", async () => {
      currentPage = west;
      step("attempt cross-merchant redemption");
      await openWorkbench(west);
      const response = await redeem(west, coffeeCode, 403);
      assert.notEqual((await response.json()).code, "OK");
      await west.locator("#redemption-error").waitFor({ state: "visible" });
      assert.equal(await west.locator("#redemption-result").isVisible(), false);
      await west.waitForFunction(() => !document.querySelector("#redemption-submit").disabled);
      await west.locator("#redemption-code").fill("");
    });

    const east = await newPage();
    await scenario("Owning merchant redeems once and receives 409 for a duplicate", async () => {
      step("east merchant signs in");
      await login(east, "13900000001");
      await openWorkbench(east);
      step("successful redemption");
      const success = await redeem(east, coffeeCode, 200);
      assert.equal((await success.json()).data.status, "REDEEMED");
      await visibleText(east, "#redemption-result", "模拟核销成功");
      await east.waitForFunction(() => !document.querySelector("#redemption-submit").disabled);
      step("duplicate redemption rejected");
      const duplicate = await redeem(east, coffeeCode, 409);
      assert.equal((await duplicate.json()).code, "ALREADY_REDEEMED");
      await visibleText(east, "#redemption-error", "已核销");
      await east.waitForFunction(() => !document.querySelector("#redemption-submit").disabled);
      await east.locator("#redemption-code").fill("");
    });

    await scenario("Student refresh displays redeemed status and redemption time", async () => {
      currentPage = student;
      step("refresh private voucher list");
      await act(student, "/api/me/vouchers", "GET", () => student.locator("#wallet-refresh").click());
      await visibleText(student, "#wallet-list .voucher-status", "已核销");
      await visibleText(student, "#wallet-list .redeemed-time", "核销于");
      assert.ok(!(await student.locator("#wallet-list .redeemed-time").textContent()).includes("—"));
    });

    const walletRoute = (url) => url.origin === origin && url.pathname === "/api/me/vouchers";
    await scenario("Injected 503 preserves the active session and allows recovery", async () => {
      currentPage = student;
      step("inject private-list service outage");
      await student.route(walletRoute, (route) => route.fulfill({ status: 503, contentType: "application/json",
        json: { code: "AUTH_UNAVAILABLE", message: "测试：认证服务暂不可用", data: null, requestId: "browser-fixture-503" } }), { times: 1 });
      await act(student, "/api/me/vouchers", "GET", () => student.locator("#wallet-refresh").click(), 503);
      await visibleText(student, "#wallet-list", "优惠券读取失败");
      assert.equal(await student.locator("#logout-button").isVisible(), true);
      assert.equal(await student.locator("#login-open").isVisible(), false);
      step("retry after outage");
      await act(student, "/api/me/vouchers", "GET", () => student.locator("#wallet-refresh").click());
      await visibleText(student, "#wallet-list .voucher-status", "已核销");
    });

    await scenario("Injected 401 clears the session and all displayed private vouchers", async () => {
      currentPage = student;
      step("inject expired session response");
      await student.route(walletRoute, (route) => route.fulfill({ status: 401, contentType: "application/json",
        json: { code: "UNAUTHORIZED", message: "测试：会话已失效", data: null, requestId: "browser-fixture-401" } }), { times: 1 });
      await act(student, "/api/me/vouchers", "GET", () => student.locator("#wallet-refresh").click(), 401);
      await student.locator("#login-open").waitFor({ state: "visible" });
      await visibleText(student, "#wallet-list", "登录后查看自己的优惠券");
      await count(student, "#wallet-list .owned-voucher", 0);
      assert.equal(await student.evaluate(() => sessionStorage.getItem("campuslife.demo.token")), null);
    });

    await scenario("A delayed private merchant response cannot repopulate the page after logout", async () => {
      currentPage = west;
      step("delay an already-authorized merchant list response");
      let release;
      let signalCaptured;
      const gate = new Promise((resolve) => { release = resolve; });
      const captured = new Promise((resolve) => { signalCaptured = resolve; });
      const merchantRoute = (url) => url.origin === origin && url.pathname === "/api/merchant/shops";
      await west.route(merchantRoute, async (route) => {
        const response = await route.fetch({ maxRedirects: 0 });
        signalCaptured();
        await gate;
        await route.fulfill({ response });
      }, { times: 1 });
      const delayedResponse = west.waitForResponse((response) => isRequest(response, "/api/merchant/shops"));
      await west.locator("#merchant-refresh").click();
      let captureTimeout;
      try {
        await Promise.race([captured, new Promise((_, reject) => {
          captureTimeout = setTimeout(() => reject(new Error("capture timeout")), 10000);
        })]);
        step("logout before releasing old response");
        await logout(west);
      } finally { clearTimeout(captureTimeout); release(); }
      await (await delayedResponse).finished();
      // Wait beyond JSON parsing and the next paint without a timing-dependent sleep.
      await west.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await count(west, "#merchant-list .managed-shop", 0);
      assert.equal(await west.locator("#merchant-list").textContent(), "");
      assert.equal(await west.locator("#merchant-view").isVisible(), false);
      assert.equal(await west.locator("#merchant-dialog").isVisible(), false);
      assert.equal(await west.locator("#redemption-result").textContent(), "");
      assert.equal(await west.locator("#redemption-code").inputValue(), "");
    });

    await scenario("OpenAPI declares bearer authentication for all eight protected operations", async () => {
      step("read same-origin OpenAPI metadata without credentials");
      const response = await student.context().request.get(origin + "/v3/api-docs", { maxRedirects: 0 });
      assert.equal(response.status(), 200);
      const spec = await response.json();
      const protectedOperations = [["/api/auth/me", "get"], ["/api/auth/logout", "post"],
        ["/api/shops/{id}", "patch"], ["/api/vouchers/{id}/claims", "post"],
        ["/api/me/vouchers", "get"], ["/api/merchant/shops", "get"],
        ["/api/merchant/shops/{id}", "get"], ["/api/merchant/vouchers/redemptions", "post"]];
      for (const [apiPath, method] of protectedOperations) {
        assert.ok(spec.paths[apiPath]?.[method]?.security?.some((requirement) => Array.isArray(requirement.bearerAuth)),
          "A protected operation is missing its bearer requirement");
      }
      assert.equal(spec.components.securitySchemes.bearerAuth.scheme, "bearer");
      assert.equal(externalRequests.length, 0, "Unexpected non-fixture network request");
    });
    report.passed = true;
  } catch {
    failure = true;
    report.failureStage = currentStep;
    await safeFailureScreenshot();
  } finally {
    for (const context of contexts) { try { await context.close(); } catch { /* Continue cleanup. */ } }
    if (browser) { try { await browser.close(); } catch { failure = true; report.passed = false; } }
    await fs.writeFile(path.join(output, "results.json"), JSON.stringify(report, null, 2) + "\n", { mode: 0o600 });
    console.log("Sanitized browser results: target/browser-tests/results.json");
  }
  if (failure) process.exitCode = 1;
})().catch(() => {
  console.error("Browser verification could not finish; no session details were logged.");
  process.exitCode = 1;
});
