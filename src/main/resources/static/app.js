"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const TOKEN_KEY = "campuslife.demo.token";
  const state = { token: readToken(), user: null, view: "discover", shopPage: 1, shopTotal: 0,
    walletPage: 1, walletTotal: 0, offerPage: 1, offerTotal: 0, shop: null,
    merchantPage: 1, merchantTotal: 0, managedShop: null, managedShopId: null, sessionEpoch: 0,
    filters: {}, shopRequest: 0, walletRequest: 0, detailRequest: 0, offerRequest: 0,
    merchantRequest: 0, managerRequest: 0, redemptionRequest: 0 };
  const PAGE_SIZE = 6;

  function readToken() { try { return sessionStorage.getItem(TOKEN_KEY) || ""; } catch { return ""; } }
  function saveToken(token) {
    state.token = token;
    try { if (token) sessionStorage.setItem(TOKEN_KEY, token); else sessionStorage.removeItem(TOKEN_KEY); } catch { /* Memory-only session if storage is unavailable. */ }
  }
  function node(tag, className, text) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text !== undefined) item.textContent = text;
    return item;
  }
  function money(cents) { return new Intl.NumberFormat("zh-CN", { style: "currency", currency: "CNY", maximumFractionDigits: 2 }).format(cents / 100); }
  function toCents(value) {
    const raw = value.trim();
    if (!/^\d+(?:\.\d{1,2})?$/.test(raw)) throw new Error("金额需为非负数字，最多保留两位小数。");
    const [whole, decimal = ""] = raw.split(".");
    const result = Number(whole) * 100 + Number(decimal.padEnd(2, "0"));
    if (!Number.isSafeInteger(result) || result > 10000000) throw new Error("金额不能超过 100,000 元。");
    return result;
  }
  function localDate(utc) {
    if (!utc) return "—";
    const date = new Date(/[zZ]|[+-]\d{2}:\d{2}$/.test(utc) ? utc : utc + "Z");
    return Number.isNaN(date.getTime()) ? "—" : new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(date);
  }
  function notice(title, message = "", requestId = "", error = false) {
    $("notice-title").textContent = title;
    $("notice-text").textContent = message;
    $("notice-text").hidden = !message;
    $("notice-request").textContent = requestId ? "请求编号：" + requestId : "";
    $("notice-request").hidden = !requestId;
    $("notice").classList.toggle("error", error);
    $("notice").hidden = false;
    if ($("shop-dialog").open && !$("login-dialog").open) {
      $("detail-notice").textContent = title + (message ? " · " + message : "") + (requestId ? " · 请求编号：" + requestId : "");
      $("detail-notice").classList.toggle("inline-error", error);
      $("detail-notice").hidden = false;
    }
  }
  function showError(error) { if (error.name !== "StaleSessionError") notice("操作未完成", error.message || "暂时无法完成请求，请稍后重试。", error.requestId || "", true); }
  function errorText(error) { return error.message + (error.requestId ? "（请求编号：" + error.requestId + "）" : ""); }
  function inlineError(error) {
    $("login-error").textContent = error.message + (error.requestId ? "（请求编号：" + error.requestId + "）" : "");
    $("login-error").hidden = false;
  }
  async function api(path, { method = "GET", data, authenticated = false } = {}) {
    const headers = { Accept: "application/json" };
    const sentToken = authenticated ? state.token : "";
    const sentEpoch = state.sessionEpoch;
    if (data !== undefined) headers["Content-Type"] = "application/json";
    if (sentToken) headers.Authorization = "Bearer " + sentToken;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(path, { method, headers, body: data === undefined ? undefined : JSON.stringify(data),
        signal: controller.signal, credentials: "omit", cache: "no-store" });
      let payload;
      try { payload = await response.json(); } catch {
        const error = new Error("服务返回了无法识别的内容，请检查后端是否正常运行。");
        error.requestId = response.headers.get("X-Request-Id") || "";
        throw error;
      }
      // A previous account's response must never populate the next account's UI.
      if (authenticated && (state.sessionEpoch !== sentEpoch || state.token !== sentToken)) {
        const error = new Error("会话已切换，本次响应已忽略。"); error.name = "StaleSessionError"; throw error;
      }
      if (!response.ok || payload.code !== "OK") {
        if (response.status === 401 && authenticated) {
          clearSession(); notice("登录已失效", "请重新登录后继续操作。", payload.requestId || "", true);
        }
        const error = new Error(payload.message || "请求未成功，请稍后重试。");
        error.requestId = payload.requestId || response.headers.get("X-Request-Id") || "";
        error.status = response.status;
        error.code = payload.code;
        throw error;
      }
      return payload.data;
    } catch (error) {
      if (error.name === "AbortError") throw new Error("请求超时，请稍后重试。");
      if (error instanceof TypeError) throw new Error("无法连接后端，请确认本地服务已经启动。");
      throw error;
    } finally { clearTimeout(timeout); }
  }
  function empty(container, title, explanation, action) {
    const box = node("div", "empty-state");
    box.append(node("strong", "", title), node("div", "", explanation || ""));
    if (action) {
      const button = node("button", "button secondary small", action.text);
      button.type = "button";
      button.addEventListener("click", action.run);
      box.append(button);
    }
    container.replaceChildren(box);
  }
  function paginate(kind, page, total) {
    const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
    $(kind + "-pagination").hidden = total <= PAGE_SIZE;
    $(kind + "-page-label").textContent = page + " / " + pages;
    $(kind + "-prev").disabled = page <= 1;
    $(kind + "-next").disabled = page >= pages;
  }
  function setAccount() {
    $("account-label").textContent = state.user ? state.user.nickname + (state.user.role === "MERCHANT" ? " · 商户" : "") : "游客浏览";
    $("login-open").hidden = Boolean(state.user);
    $("logout-button").hidden = !state.user;
    $("merchant-panel").hidden = !state.user || state.user.role !== "MERCHANT" || !state.shop;
    $("merchant-tab").hidden = !isMerchant();
  }
  function isMerchant() { return state.user?.role === "MERCHANT"; }
  function invalidatePrivateUi() {
    state.sessionEpoch++; state.walletRequest++; state.merchantRequest++; state.managerRequest++; state.redemptionRequest++;
    state.walletPage = 1; state.walletTotal = 0; state.merchantPage = 1; state.merchantTotal = 0;
    state.managedShop = null; state.managedShopId = null;
    $("wallet-list").replaceChildren(); $("wallet-pagination").hidden = true; $("wallet-refresh").disabled = false;
    $("merchant-list").replaceChildren(); $("merchant-pagination").hidden = true; $("merchant-count").textContent = "";
    $("merchant-refresh").disabled = false; $("merchant-save").disabled = false; $("merchant-reload").disabled = false;
    $("merchant-form").reset(); $("merchant-form").hidden = true;
    $("merchant-error").hidden = true; $("merchant-error").textContent = "";
    $("merchant-success").hidden = true; $("merchant-success").textContent = "";
    $("merchant-detail-title").textContent = "管理店铺"; $("merchant-detail-loading").textContent = "";
    if ($("merchant-dialog").open) $("merchant-dialog").close();
    $("redemption-form").reset(); $("redemption-code").disabled = false; $("redemption-submit").disabled = false; $("redemption-submit").textContent = "确认模拟核销";
    $("redemption-error").textContent = ""; $("redemption-error").hidden = true;
    $("redemption-result").replaceChildren(); $("redemption-result").hidden = true;
    $("notice").hidden = true; $("detail-notice").hidden = true;
    for (const id of ["notice-title", "notice-text", "notice-request", "detail-notice"]) $(id).textContent = "";
  }
  function clearSession() {
    invalidatePrivateUi(); saveToken(""); state.user = null;
    setAccount();
    if (state.view === "wallet") showWalletLogin();
    if (state.view === "merchant") switchView("discover");
  }
  function openLogin() { $("login-error").hidden = true; $("login-dialog").showModal(); }
  function showWalletLogin() { empty($("wallet-list"), "登录后查看自己的优惠券", "每个账号的领取记录独立保存。", { text: "演示登录", run: openLogin }); }
  function switchView(view) {
    if (view === "merchant" && !isMerchant()) return;
    state.view = view;
    $("discover-view").hidden = view !== "discover";
    $("wallet-view").hidden = view !== "wallet";
    $("merchant-view").hidden = view !== "merchant";
    for (const [name, tab] of [["discover", "discover-tab"], ["wallet", "wallet-tab"], ["merchant", "merchant-tab"]]) {
      $(tab).classList.toggle("active", view === name);
      if (view === name) $(tab).setAttribute("aria-current", "page"); else $(tab).removeAttribute("aria-current");
    }
    if (view === "wallet") { state.walletPage = 1; loadWallet(); }
    if (view === "merchant") { state.merchantPage = 1; loadMerchantShops(); }
  }

  async function loadShops() {
    const request = ++state.shopRequest;
    empty($("shop-list"), "正在寻找好店…", "正在读取真实后端数据。");
    $("shop-pagination").hidden = true;
    $("filter-button").disabled = true;
    try {
      const query = new URLSearchParams({ ...state.filters, page: state.shopPage, size: PAGE_SIZE });
      const result = await api("/api/shops?" + query);
      if (request !== state.shopRequest) return;
      state.shopTotal = result.total;
      $("shop-count").textContent = "找到 " + result.total + " 家店铺";
      $("shop-list").replaceChildren();
      if (!result.items.length) empty($("shop-list"), "暂时没有符合条件的店铺", "试试其他校区、类别或更高一点的预算。");
      else result.items.forEach((shop) => $("shop-list").append(shopCard(shop)));
      paginate("shop", state.shopPage, result.total);
    } catch (error) {
      if (request !== state.shopRequest) return;
      $("shop-count").textContent = "暂时无法读取";
      empty($("shop-list"), "店铺列表读取失败", error.message, { text: "重新加载", run: loadShops });
      showError(error);
    } finally { if (request === state.shopRequest) $("filter-button").disabled = false; }
  }
  function shopCard(shop) {
    const card = node("article", "shop-card");
    const art = node("div", "card-art"); art.dataset.category = shop.category;
    art.append(node("span", "art-label", "CAMPUS / " + String(shop.id).padStart(2, "0")), node("span", "campus-badge", shop.campus));
    const glyph = node("span", "art-glyph", shop.category === "咖啡" ? "咖" : shop.category === "餐饮" ? "食" : "动");
    glyph.setAttribute("aria-hidden", "true"); art.append(glyph);
    const body = node("div", "card-body");
    body.append(node("span", "card-category", shop.category), node("h3", "", shop.name), node("p", "card-description", shop.description));
    const bottom = node("div", "card-bottom");
    const price = node("span", "price", money(shop.averagePrice)); price.append(node("small", "", "/ 人均"));
    const detail = node("button", "button secondary card-button", "看看优惠 ↗");
    detail.type = "button"; detail.addEventListener("click", () => openShop(shop.id));
    bottom.append(price, detail); body.append(bottom); card.append(art, body); return card;
  }
  async function openShop(id) {
    const request = ++state.detailRequest;
    state.offerRequest++; state.shop = null; state.offerPage = 1;
    $("detail-body").hidden = true; $("detail-loading").hidden = false;
    $("detail-notice").hidden = true;
    $("detail-loading").textContent = "正在读取店铺详情…";
    if (!$("shop-dialog").open) $("shop-dialog").showModal();
    try {
      const shop = await api("/api/shops/" + id);
      if (request !== state.detailRequest || !$("shop-dialog").open) return;
      state.shop = shop; renderShop(shop);
      $("detail-loading").hidden = true; $("detail-body").hidden = false;
      await loadOffers();
    } catch (error) {
      if (request !== state.detailRequest) return;
      $("detail-loading").textContent = error.message + (error.requestId ? " · 请求编号：" + error.requestId : "");
      showError(error);
    }
  }
  function renderShop(shop) {
    $("detail-labels").replaceChildren(node("span", "", shop.campus), node("span", "", shop.category));
    $("detail-title").textContent = shop.name; $("detail-description").textContent = shop.description;
    $("detail-address").textContent = "地址 · " + shop.address;
    $("detail-price").textContent = money(shop.averagePrice) + " / 人均";
    setAccount();
  }
  async function loadOffers() {
    if (!state.shop) return;
    const request = ++state.offerRequest; const shopId = state.shop.id;
    empty($("offer-list"), "正在读取优惠…"); $("offer-pagination").hidden = true;
    try {
      const result = await api("/api/shops/" + shopId + "/vouchers?page=" + state.offerPage + "&size=" + PAGE_SIZE);
      if (request !== state.offerRequest || state.shop?.id !== shopId) return;
      state.offerTotal = result.total; $("offer-list").replaceChildren();
      if (!result.items.length) empty($("offer-list"), "暂无可领取优惠", "当前没有有效的优惠活动，可以看看其他店铺。");
      else result.items.forEach((offer) => {
        const card = node("article", "offer-card");
        const value = node("div", "offer-value", money(offer.discountAmount)); value.append(node("small", "", "满 " + money(offer.minSpend) + " 可用"));
        const info = node("div", "offer-info");
        info.append(node("h4", "", offer.title), node("p", "", "剩余 " + offer.stock + " 份 · 每人限领 1 次"), node("p", "", "领取截止 " + localDate(offer.claimEnd)));
        const button = node("button", "button", offer.stock > 0 ? "领取优惠" : "已领完"); button.type = "button"; button.disabled = offer.stock <= 0;
        button.addEventListener("click", () => claim(offer.id, button)); card.append(value, info, button); $("offer-list").append(card);
      });
      paginate("offer", state.offerPage, result.total);
    } catch (error) { if (request === state.offerRequest) { empty($("offer-list"), "优惠读取失败", error.message); showError(error); } }
  }
  async function claim(id, button) {
    if (!state.user) { openLogin(); return; }
    const epoch = state.sessionEpoch;
    button.disabled = true; button.textContent = "正在领取…";
    try {
      await api("/api/vouchers/" + id + "/claims", { method: "POST", authenticated: true });
      if (epoch !== state.sessionEpoch) return;
      notice("优惠券已收好", "可前往“我的优惠券”查看领取记录与使用期限。");
      await loadOffers(); if (state.view === "wallet") await loadWallet();
    } catch (error) { if (epoch === state.sessionEpoch) showError(error); }
    finally { button.disabled = false; button.textContent = "领取优惠"; }
  }
  async function loadWallet() {
    const request = ++state.walletRequest;
    $("wallet-pagination").hidden = true;
    if (!state.user) { showWalletLogin(); return; }
    empty($("wallet-list"), "正在读取你的优惠券…");
    $("wallet-refresh").disabled = true;
    try {
      const result = await api("/api/me/vouchers?page=" + state.walletPage + "&size=" + PAGE_SIZE, { authenticated: true });
      if (request !== state.walletRequest) return;
      state.walletTotal = result.total; $("wallet-list").replaceChildren();
      if (!result.items.length) empty($("wallet-list"), "还没有领取优惠券", "先去发现好店，遇到喜欢的优惠就收下吧。", { text: "发现好店", run: () => switchView("discover") });
      else result.items.forEach((voucher) => {
        const card = node("article", "owned-voucher");
        card.dataset.voucherOrderId = voucher.id;
        card.append(node("span", "voucher-status" + (voucher.status === "REDEEMED" ? " redeemed" : ""), voucher.status === "REDEEMED" ? "已核销" : voucher.status === "ISSUED" ? "已领取" : voucher.status), node("h3", "", voucher.voucherTitle), node("p", "", voucher.shopName), node("p", "", "使用截止 " + localDate(voucher.expiresAt)), node("p", "", "演示核销码"), node("code", "", voucher.redeemCode), node("p", "", "领取于 " + localDate(voucher.createdAt)));
        if (voucher.redeemedAt) card.append(node("p", "redeemed-time", "核销于 " + localDate(voucher.redeemedAt)));
        $("wallet-list").append(card);
      });
      paginate("wallet", state.walletPage, result.total);
    } catch (error) {
      if (request === state.walletRequest) { empty($("wallet-list"), "优惠券读取失败", error.message, { text: "重试", run: loadWallet }); showError(error); }
    } finally { if (request === state.walletRequest) $("wallet-refresh").disabled = false; }
  }

  async function loadMerchantShops() {
    const request = ++state.merchantRequest;
    if (!isMerchant()) return;
    empty($("merchant-list"), "正在读取你的店铺…");
    $("merchant-pagination").hidden = true; $("merchant-refresh").disabled = true;
    try {
      const result = await api("/api/merchant/shops?page=" + state.merchantPage + "&size=" + PAGE_SIZE, { authenticated: true });
      if (request !== state.merchantRequest) return;
      state.merchantTotal = result.total; $("merchant-count").textContent = "共 " + result.total + " 家店铺，包含已下线店铺";
      $("merchant-list").replaceChildren();
      if (!result.items.length) empty($("merchant-list"), "当前账号还没有店铺", "演示项目的店铺由预设数据提供。");
      else result.items.forEach((shop) => {
        const card = node("article", "managed-shop"); card.dataset.shopId = shop.id;
        const copy = node("div", "managed-shop-copy");
        copy.append(node("span", "shop-status" + (shop.status === 0 ? " offline" : ""), shop.status === 1 ? "已上线" : "已下线"), node("h3", "", shop.name), node("p", "muted", shop.campus + " · " + shop.category + " · " + money(shop.averagePrice) + " / 人均"));
        const button = node("button", "button secondary", "管理店铺"); button.type = "button";
        button.setAttribute("aria-label", "管理店铺：" + shop.name); button.addEventListener("click", () => openMerchantShop(shop.id));
        card.append(copy, button); $("merchant-list").append(card);
      });
      paginate("merchant", state.merchantPage, result.total);
    } catch (error) {
      if (request !== state.merchantRequest) return;
      $("merchant-count").textContent = "暂时无法读取";
      empty($("merchant-list"), "店铺管理列表读取失败", error.message, { text: "重新加载", run: loadMerchantShops }); showError(error);
    } finally { if (request === state.merchantRequest) $("merchant-refresh").disabled = false; }
  }
  async function openMerchantShop(id) {
    if (!isMerchant()) return;
    const request = ++state.managerRequest;
    state.managedShop = null; state.managedShopId = id;
    if ($("shop-dialog").open) $("shop-dialog").close();
    $("merchant-form").hidden = true; $("merchant-error").hidden = true; $("merchant-success").hidden = true;
    $("merchant-save").disabled = false; $("merchant-reload").disabled = false;
    $("merchant-detail-title").textContent = "管理店铺";
    $("merchant-detail-loading").textContent = "正在读取店铺信息…"; $("merchant-detail-loading").hidden = false;
    if (!$("merchant-dialog").open) $("merchant-dialog").showModal();
    try {
      const shop = await api("/api/merchant/shops/" + id, { authenticated: true });
      if (request !== state.managerRequest || !$("merchant-dialog").open) return;
      state.managedShop = shop; renderManagedShop(shop);
      $("merchant-detail-loading").hidden = true; $("merchant-form").hidden = false;
    } catch (error) {
      if (request !== state.managerRequest) return;
      $("merchant-detail-loading").hidden = true;
      $("merchant-error").textContent = errorText(error); $("merchant-error").hidden = false;
    }
  }
  function renderManagedShop(shop) {
    $("merchant-detail-title").textContent = "管理 · " + shop.name;
    $("merchant-name").value = shop.name; $("merchant-price").value = (shop.averagePrice / 100).toFixed(2);
    $("merchant-status").value = String(shop.status);
  }

  $("filter-form").addEventListener("submit", (event) => {
    event.preventDefault();
    try {
      const filters = {};
      if ($("campus-filter").value) filters.campus = $("campus-filter").value;
      if ($("category-filter").value) filters.category = $("category-filter").value;
      if ($("price-filter").value.trim()) filters.maxPrice = toCents($("price-filter").value);
      state.filters = filters; state.shopPage = 1; loadShops();
    } catch (error) { showError(error); }
  });
  $("filter-reset").addEventListener("click", () => { $("filter-form").reset(); state.filters = {}; state.shopPage = 1; loadShops(); });
  $("discover-tab").addEventListener("click", () => switchView("discover"));
  $("wallet-tab").addEventListener("click", () => switchView("wallet"));
  $("merchant-tab").addEventListener("click", () => switchView("merchant"));
  $("wallet-refresh").addEventListener("click", loadWallet);
  $("merchant-refresh").addEventListener("click", loadMerchantShops);
  for (const [kind, loader] of [["shop", loadShops], ["wallet", loadWallet], ["offer", loadOffers], ["merchant", loadMerchantShops]]) {
    $(kind + "-prev").addEventListener("click", () => { if (state[kind + "Page"] > 1) { state[kind + "Page"]--; loader(); } });
    $(kind + "-next").addEventListener("click", () => { if (state[kind + "Page"] * PAGE_SIZE < state[kind + "Total"]) { state[kind + "Page"]++; loader(); } });
  }
  $("login-open").addEventListener("click", openLogin);
  $("login-close").addEventListener("click", () => $("login-dialog").close());
  $("shop-close").addEventListener("click", () => $("shop-dialog").close());
  $("shop-dialog").addEventListener("close", () => { state.detailRequest++; state.offerRequest++; });
  $("merchant-close").addEventListener("click", () => $("merchant-dialog").close());
  $("merchant-dialog").addEventListener("close", () => {
    if (!$("merchant-dialog").open) { state.managerRequest++; state.managedShop = null; state.managedShopId = null; }
  });
  $("detail-manage").addEventListener("click", () => { if (state.shop) openMerchantShop(state.shop.id); });
  $("merchant-reload").addEventListener("click", () => { if (state.managedShopId !== null) openMerchantShop(state.managedShopId); });
  $("notice-close").addEventListener("click", () => { $("notice").hidden = true; });
  $("login-phone").addEventListener("change", () => { $("login-code").value = ""; $("code-preview").hidden = true; $("login-error").hidden = true; });
  $("code-button").addEventListener("click", async () => {
    const button = $("code-button"); const phone = $("login-phone").value;
    button.disabled = true; button.textContent = "正在获取…"; $("login-error").hidden = true;
    try {
      const data = await api("/api/auth/code", { method: "POST", data: { phone } });
      if ($("login-phone").value !== phone) return;
      $("login-code").value = data.code;
      $("code-preview").textContent = "本次模拟验证码：" + data.code + "，有效期 " + data.expiresInSeconds + " 秒。已填入输入框，请点击登录。";
      $("code-preview").hidden = false;
    } catch (error) { inlineError(error); }
    finally { button.disabled = false; button.textContent = "获取模拟码"; }
  });
  $("login-form").addEventListener("submit", async (event) => {
    event.preventDefault(); const button = $("login-submit"); button.disabled = true; $("login-error").hidden = true;
    const epoch = state.sessionEpoch;
    try {
      const result = await api("/api/auth/login", { method: "POST", data: { phone: $("login-phone").value, code: $("login-code").value } });
      if (epoch !== state.sessionEpoch) return;
      invalidatePrivateUi(); saveToken(result.token); state.user = result.user;
      $("login-code").value = ""; $("code-preview").hidden = true; $("login-dialog").close(); setAccount();
      notice("登录成功", "欢迎，" + state.user.nickname + "。现在可以领取优惠券。");
      if (state.view === "wallet") await loadWallet();
    } catch (error) { if (epoch === state.sessionEpoch) inlineError(error); }
    finally { button.disabled = false; }
  });
  $("logout-button").addEventListener("click", async () => {
    const button = $("logout-button"); button.disabled = true;
    const epoch = state.sessionEpoch;
    try { await api("/api/auth/logout", { method: "POST", authenticated: true }); if (epoch !== state.sessionEpoch) return; clearSession(); notice("已退出登录", "当前会话已失效。"); }
    catch (error) { if (epoch === state.sessionEpoch) showError(error); }
    finally { button.disabled = false; }
  });
  $("merchant-form").addEventListener("submit", async (event) => {
    event.preventDefault(); if (!state.managedShop || !isMerchant()) return;
    const shop = state.managedShop; const request = state.managerRequest; const epoch = state.sessionEpoch;
    const button = $("merchant-save"); button.disabled = true; $("merchant-reload").disabled = true;
    $("merchant-error").hidden = true; $("merchant-success").hidden = true;
    try {
      const updated = await api("/api/shops/" + shop.id, { method: "PATCH", authenticated: true, data: {
        name: $("merchant-name").value.trim(), averagePrice: toCents($("merchant-price").value), status: Number($("merchant-status").value), version: shop.version } });
      if (epoch !== state.sessionEpoch) return;
      if (request === state.managerRequest && $("merchant-dialog").open) {
        state.managedShop = updated; renderManagedShop(updated);
        $("merchant-success").textContent = updated.status === 0 ? "店铺已下线。仍可在此或商户工作台管理并重新上线，已有领取记录继续保留。" : "店铺信息已保存，当前已上线展示。";
        $("merchant-success").hidden = false;
      }
      await Promise.all([loadShops(), loadMerchantShops()]);
    } catch (error) {
      if (request !== state.managerRequest || epoch !== state.sessionEpoch) return;
      $("merchant-error").textContent = errorText(error) + (error.code === "VERSION_CONFLICT" ? " 请重新读取最新信息后再修改。" : "");
      $("merchant-error").hidden = false;
    }
    finally { if (request === state.managerRequest) { button.disabled = false; $("merchant-reload").disabled = false; } }
  });
  $("redemption-form").addEventListener("submit", async (event) => {
    event.preventDefault(); if (!isMerchant()) return;
    const request = ++state.redemptionRequest;
    const button = $("redemption-submit"); button.disabled = true; button.textContent = "正在核销…";
    $("redemption-code").disabled = true;
    $("redemption-error").hidden = true; $("redemption-result").hidden = true; $("redemption-result").replaceChildren();
    try {
      const result = await api("/api/merchant/vouchers/redemptions", { method: "POST", authenticated: true, data: { redeemCode: $("redemption-code").value.trim() } });
      if (request !== state.redemptionRequest) return;
      $("redemption-result").append(node("strong", "", "模拟核销成功"), node("p", "", result.voucherTitle + " · " + result.shopName), node("p", "", "核销时间：" + localDate(result.redeemedAt)), node("p", "muted", "该优惠券已标记为已核销，不能再次使用。"));
      $("redemption-result").hidden = false;
      $("redemption-code").value = "";
    } catch (error) {
      if (request !== state.redemptionRequest) return;
      $("redemption-error").textContent = errorText(error); $("redemption-error").hidden = false;
    } finally { if (request === state.redemptionRequest) { button.disabled = false; button.textContent = "确认模拟核销"; $("redemption-code").disabled = false; } }
  });

  async function restoreSession() {
    if (!state.token) return;
    const token = state.token;
    try {
      const user = await api("/api/auth/me", { authenticated: true });
      if (state.token !== token) return;
      state.user = user; setAccount(); if (state.view === "wallet") loadWallet();
    }
    catch (error) { if (error.status !== 401) showError(error); }
  }
  setAccount(); loadShops(); restoreSession();
})();
