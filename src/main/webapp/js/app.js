(() => {
    "use strict";

    const $ = (selector) => document.querySelector(selector);
    const state = {
        account: null,
        csrfToken: null,
        networkId: null,
        privateKey: null,
        statusTimer: null,
        statusPollToken: 0,
        statusPollCount: 0
    };

    const notice = $("#notice");
    const encoder = new TextEncoder();

    function apiUrl(path) {
        const base = new URL("./", window.location.href);
        return new URL(path.replace(/^\/+/, ""), base).toString();
    }

    function showNotice(message, kind = "info") {
        notice.textContent = message;
        notice.dataset.kind = kind;
        notice.hidden = !message;
    }

    function showResult(element, message, transactionId) {
        element.replaceChildren();
        const text = document.createElement("span");
        text.textContent = message;
        element.append(text);
        if (transactionId) {
            const line = document.createElement("p");
            const label = document.createElement("strong");
            label.textContent = "Transaction ID: ";
            const id = document.createElement("code");
            id.textContent = transactionId;
            line.append(label, id);
            element.append(line);
            const trackButton = document.createElement("button");
            trackButton.type = "button";
            trackButton.className = "button button-secondary";
            trackButton.textContent = "Track status";
            trackButton.addEventListener("click", () => {
                $("#transaction-id").value = transactionId;
                activateView("lookup-view");
                run(() => startStatusTracking(transactionId));
            });
            element.append(trackButton);
        }
        element.hidden = false;
    }

    async function api(path, options = {}) {
        const method = (options.method || "GET").toUpperCase();
        const headers = new Headers(options.headers || {});
        if (options.body !== undefined) {
            headers.set("Content-Type", "application/json");
        }
        if (!["GET", "HEAD", "OPTIONS"].includes(method) && state.csrfToken) {
            headers.set("X-CSRF-Token", state.csrfToken);
        }
        const response = await fetch(apiUrl(path), {
            ...options,
            method,
            headers,
            credentials: "same-origin"
        });
        const body = await response.json().catch(() => null);
        if (!response.ok) {
            if (response.status === 401) {
                resetSession();
            }
            const message = body?.message || body?.data?.code || `Request failed (${response.status})`;
            throw new Error(message);
        }
        return body;
    }

    function resetSession() {
        state.account = null;
        state.csrfToken = null;
        state.privateKey = null;
        window.clearTimeout(state.statusTimer);
        state.statusTimer = null;
        state.statusPollToken++;
        $("#workspace").hidden = true;
        $("#login-view").hidden = false;
        $("#logout-button").hidden = true;
        $("#account-label").hidden = true;
        $("#key-state").textContent = "Import a registered PKCS#8 P-256 private key to sign operations.";
        $("#private-key-file").value = "";
    }

    function displaySession(account, csrfToken) {
        state.account = account;
        state.csrfToken = csrfToken;
        $("#login-view").hidden = true;
        $("#workspace").hidden = false;
        $("#logout-button").hidden = false;
        $("#account-label").hidden = false;
        $("#account-label").textContent = account.username;
        $("#role-badge").textContent = account.organizationId
            ? `${account.role} · ${account.organizationId}`
            : account.role;
        showNotice("", "info");
    }

    function canonicalJson(value) {
        if (value === null || typeof value === "string" || typeof value === "boolean") {
            return JSON.stringify(value);
        }
        if (typeof value === "number") {
            if (!Number.isSafeInteger(value)) {
                throw new Error("Use strings for decimal or large numeric values so the signed data is exact.");
            }
            return JSON.stringify(value);
        }
        if (Array.isArray(value)) {
            return `[${value.map(canonicalJson).join(",")}]`;
        }
        if (typeof value === "object") {
            const keys = Object.keys(value).sort();
            return `{${keys.map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
        }
        throw new Error("The signed payload contains an unsupported JSON value.");
    }

    function bytesToBase64(bytes) {
        let binary = "";
        for (const byte of bytes) {
            binary += String.fromCharCode(byte);
        }
        return btoa(binary);
    }

    async function signPayload(payload, purpose, organizationId, keyId) {
        if (!state.privateKey) {
            throw new Error("Import your registered organization key before signing.");
        }
        if (!state.account?.organizationId
                || state.account.organizationId !== organizationId) {
            throw new Error("The signing organization must match the signed-in account.");
        }
        const signingObject = {
            ...payload,
            purpose,
            signerOrganizationId: organizationId,
            keyId
        };
        const signature = await crypto.subtle.sign(
            { name: "ECDSA", hash: "SHA-256" },
            state.privateKey,
            encoder.encode(canonicalJson(signingObject))
        );
        if (signature.byteLength !== 64) {
            throw new Error("This browser returned an unsupported ECDSA signature format.");
        }
        return bytesToBase64(new Uint8Array(signature));
    }

    function eventPurpose(eventType, role) {
        const purposes = {
            HARVESTED: { FARMER: "FARMER_HARVEST" },
            PACKAGED: { FARMER: "FARMER_PACKAGED" },
            RECEIVED: {
                WAREHOUSE: "WAREHOUSE_RECEIPT",
                RETAILER: "RETAILER_RECEIPT"
            },
            SOLD: { RETAILER: "RETAILER_SALE" },
            CORRECTION: {
                FARMER: "CORRECTION",
                CARRIER: "CORRECTION",
                WAREHOUSE: "CORRECTION",
                RETAILER: "CORRECTION"
            }
        };
        const purpose = purposes[eventType]?.[role];
        if (!purpose) {
            throw new Error(`Role ${role} cannot submit ${eventType} events.`);
        }
        return purpose;
    }

    function defaultDataFor(eventType) {
        const defaults = {
            HARVESTED: {
                productType: "Mango",
                variety: "Cat Hoa Loc",
                harvestDate: new Date().toISOString().slice(0, 10),
                quantity: "1200.000",
                quantityUnit: "kg",
                farmName: "",
                province: ""
            },
            PACKAGED: { packageType: "", quantity: "1200.000", quantityUnit: "kg" },
            RECEIVED: { shipmentTxId: "" },
            SOLD: { saleDate: new Date().toISOString().slice(0, 10), quantity: "1200.000" },
            CORRECTION: { correctionOfTxId: "", reason: "", correctedPublicData: {} }
        };
        $("#event-data").value = JSON.stringify(defaults[eventType], null, 2);
    }

    async function importPrivateKey() {
        const file = $("#private-key-file").files[0];
        const keyId = $("#key-id").value.trim();
        if (!file || !keyId) {
            throw new Error("Choose a PKCS#8 private-key file and enter its registered key ID.");
        }
        if (!window.crypto?.subtle) {
            throw new Error("Web Crypto requires a secure context (HTTPS or localhost).");
        }
        const input = await file.arrayBuffer();
        let pkcs8 = input;
        const decoded = new TextDecoder().decode(input);
        if (decoded.includes("-----BEGIN PRIVATE KEY-----")) {
            const base64 = decoded
                .replace(/-----BEGIN PRIVATE KEY-----/g, "")
                .replace(/-----END PRIVATE KEY-----/g, "")
                .replace(/\s/g, "");
            const binary = atob(base64);
            pkcs8 = Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer;
        }
        state.privateKey = await crypto.subtle.importKey(
            "pkcs8",
            pkcs8,
            { name: "ECDSA", namedCurve: "P-256" },
            false,
            ["sign"]
        );
        $("#key-state").textContent = `Key ${keyId} imported in this tab's memory.`;
        showNotice("Signing key imported locally. It has not been uploaded.", "success");
    }

    async function submitBatchEvent() {
        const eventType = $("#event-type").value;
        const batchCode = $("#batch-code").value.trim();
        const eventId = crypto.randomUUID();
        const eventTime = new Date().toISOString();
        const keyId = $("#key-id").value.trim();
        const purpose = eventPurpose(eventType, state.account.role);
        let data;
        try {
            data = JSON.parse($("#event-data").value);
        } catch {
            throw new Error("Event data must be valid JSON.");
        }
        if (!data || Array.isArray(data) || typeof data !== "object") {
            throw new Error("Event data must be a JSON object.");
        }
        const payload = {
            networkId: state.networkId,
            eventId,
            batchCode,
            eventType,
            eventTime,
            data
        };
        const signature = await signPayload(
            payload, purpose, state.account.organizationId, keyId);
        const body = {
            eventId,
            batchCode,
            eventType,
            eventTime,
            data,
            signatures: [{
                organizationId: state.account.organizationId,
                keyId,
                purpose,
                signature
            }]
        };
        const path = eventType === "HARVESTED"
            ? "/api/v1/batches"
            : `/api/v1/batches/${encodeURIComponent(batchCode)}/events`;
        const result = await api(path, { method: "POST", body: JSON.stringify(body) });
        showResult($("#event-result"), result.message, result.data?.transactionId);
    }

    function toUtcTimestamp(localDateTime) {
        const date = new Date(localDateTime);
        if (Number.isNaN(date.getTime())) {
            throw new Error("Choose a valid proposal expiration time.");
        }
        return date.toISOString();
    }

    async function submitShipmentProposal() {
        const proposalId = crypto.randomUUID();
        const eventId = crypto.randomUUID();
        const eventTime = new Date().toISOString();
        const batchCode = $("#shipment-batch").value.trim();
        const data = {
            senderOrganizationId: state.account.organizationId,
            carrierOrganizationId: $("#carrier-organization").value.trim(),
            recipientOrganizationId: $("#recipient-organization").value.trim(),
            fromProvince: $("#from-province").value.trim(),
            toProvince: $("#to-province").value.trim(),
            expiresAt: toUtcTimestamp($("#shipment-expires").value)
        };
        const keyId = $("#key-id").value.trim();
        const payload = {
            networkId: state.networkId,
            eventId,
            batchCode,
            eventType: "SHIPPED",
            eventTime,
            data
        };
        const signature = await signPayload(
            payload, "SHIPMENT_SENDER", state.account.organizationId, keyId);
        const requestBody = {
            proposalId,
            eventId,
            batchCode,
            eventTime,
            senderOrganizationId: state.account.organizationId,
            carrierOrganizationId: data.carrierOrganizationId,
            recipientOrganizationId: data.recipientOrganizationId,
            fromProvince: data.fromProvince,
            toProvince: data.toProvince,
            expiresAt: data.expiresAt,
            signature: { keyId, purpose: "SHIPMENT_SENDER", value: signature }
        };
        const result = await api(
            `/api/v1/batches/${encodeURIComponent(batchCode)}/shipments`,
            { method: "POST", body: JSON.stringify(requestBody) }
        );
        showResult(
            $("#shipment-result"),
            `${result.message} · Proposal ${result.data?.proposalId || proposalId}`,
            result.data?.transactionId
        );
    }

    function addText(parent, tagName, text, className) {
        const element = document.createElement(tagName);
        element.textContent = text;
        if (className) {
            element.className = className;
        }
        parent.append(element);
        return element;
    }

    async function refreshInbox() {
        const container = $("#shipment-inbox");
        container.replaceChildren();
        const result = await api("/api/v1/shipments/inbox");
        const proposals = result.data?.proposals || [];
        if (proposals.length === 0) {
            addText(container, "div", "There are no active shipment proposals for this organization.", "empty-state");
            return;
        }
        for (const proposal of proposals) {
            const card = document.createElement("article");
            card.className = "proposal-card";
            addText(card, "h3", `${proposal.batchCode} · ${proposal.status}`);
            addText(card, "p", `From ${proposal.senderOrganizationId} to ${proposal.recipientOrganizationId}`);
            addText(card, "p", `${proposal.fromProvince} → ${proposal.toProvince}`);
            addText(card, "p", `Expires ${proposal.expiresAt}`);
            addText(card, "p", `Proposal ${proposal.proposalId}`);
            const actions = document.createElement("div");
            actions.className = "proposal-actions";
            const endorseButton = document.createElement("button");
            endorseButton.type = "button";
            endorseButton.className = "button button-primary";
            endorseButton.textContent = "Endorse and submit";
            endorseButton.addEventListener("click", () => endorseProposal(proposal, card));
            actions.append(endorseButton);
            card.append(actions);
            container.append(card);
        }
    }

    async function endorseProposal(proposal, card) {
        if (state.account.organizationId !== proposal.carrierOrganizationId) {
            throw new Error("This proposal is not addressed to the signed-in organization.");
        }
        const keyId = $("#key-id").value.trim();
        const data = {
            senderOrganizationId: proposal.senderOrganizationId,
            carrierOrganizationId: proposal.carrierOrganizationId,
            recipientOrganizationId: proposal.recipientOrganizationId,
            fromProvince: proposal.fromProvince,
            toProvince: proposal.toProvince,
            expiresAt: proposal.expiresAt
        };
        const payload = {
            networkId: state.networkId,
            eventId: proposal.eventId,
            batchCode: proposal.batchCode,
            eventType: "SHIPPED",
            eventTime: proposal.eventTime,
            data
        };
        const value = await signPayload(
            payload,
            "SHIPMENT_CARRIER",
            state.account.organizationId,
            keyId
        );
        const result = await api(
            `/api/v1/shipments/${encodeURIComponent(proposal.proposalId)}/endorsements`,
            {
                method: "POST",
                body: JSON.stringify({ keyId, purpose: "SHIPMENT_CARRIER", value })
            }
        );
        showResult(card, result.message, result.data?.transactionId);
        showNotice(result.message, "success");
    }

    async function loadPublicTrace(batchCode) {
        const encoded = encodeURIComponent(batchCode.trim());
        const result = await fetch(apiUrl(`/api/v1/public/batches/${encoded}/trace`), {
            credentials: "same-origin"
        });
        const body = await result.json().catch(() => null);
        if (!result.ok) {
            throw new Error(body?.message || `Trace lookup failed (${result.status}).`);
        }
        const url = new URL("./", window.location.href);
        url.searchParams.set("trace", batchCode.trim());
        $("#trace-link").href = url.toString();
        $("#trace-qr").src = apiUrl(`/api/v1/public/trace-qr/${encoded}`);
        $("#trace-json").textContent = JSON.stringify(body.data, null, 2);
        $("#trace-result").hidden = false;
    }

    async function startStatusTracking(id) {
        window.clearTimeout(state.statusTimer);
        state.statusPollCount = 0;
        const token = ++state.statusPollToken;
        await refreshTransactionStatus(id, token);
    }

    async function refreshTransactionStatus(id, token) {
        if (token !== state.statusPollToken) {
            return;
        }
        const result = await api(`/api/v1/transactions/${encodeURIComponent(id)}`);
        if (token !== state.statusPollToken) {
            return;
        }
        showResult($("#transaction-result"), `${result.message} · ${result.data.status}`, id);
        const details = document.createElement("pre");
        details.className = "json-result";
        details.textContent = JSON.stringify(result.data, null, 2);
        $("#transaction-result").append(details);
        if (result.data.status === "PENDING") {
            if (state.statusPollCount < 120) {
                state.statusPollCount++;
                showNotice(`Transaction is pending. Checking again in 5 seconds (${state.statusPollCount}/120).`, "info");
                state.statusTimer = window.setTimeout(
                    () => run(() => refreshTransactionStatus(id, token)),
                    5000
                );
            } else {
                showNotice("The transaction is still pending after 10 minutes. You can check again later.", "info");
            }
        } else {
            showNotice(`Transaction status: ${result.data.status}.`, "success");
        }
    }

    function activateView(viewId) {
        document.querySelectorAll(".workspace-view").forEach((view) => {
            view.hidden = view.id !== viewId;
        });
        document.querySelectorAll(".nav-item").forEach((button) => {
            button.classList.toggle("is-active", button.dataset.view === viewId);
        });
        if (viewId === "inbox-view") {
            run(refreshInbox);
        }
    }

    async function run(operation) {
        try {
            await operation();
        } catch (error) {
            showNotice(error.message || "The request could not be completed.", "error");
        }
    }

    function runWithButton(button, operation) {
        if (!button) {
            return run(operation);
        }
        const previousText = button.textContent;
        button.disabled = true;
        button.textContent = "Please wait…";
        return run(async () => {
            try {
                await operation();
            } finally {
                button.disabled = false;
                button.textContent = previousText;
            }
        });
    }

    async function initialize() {
        try {
            const network = await api("/api/v1/network");
            state.networkId = network.data.networkId;
            $("#network-label").textContent = state.networkId;
            $("#footer-network").textContent = `Network ${state.networkId}`;
        } catch (error) {
            $("#network-label").textContent = "Node unavailable";
            showNotice(error.message, "error");
        }

        try {
            const result = await api("/api/v1/auth/me");
            displaySession(result.data, result.data.csrfToken);
        } catch (error) {
            resetSession();
            if (!state.networkId) {
                showNotice("The node is not ready. Check the deployment and try again.", "error");
            }
        }

        const trace = new URLSearchParams(window.location.search).get("trace");
        if (trace) {
            activateView("lookup-view");
            $("#trace-batch-code").value = trace;
            await run(() => loadPublicTrace(trace));
        }
    }

    $("#login-form").addEventListener("submit", (event) => {
        event.preventDefault();
        runWithButton(event.submitter, async () => {
            const result = await api("/api/v1/auth/login", {
                method: "POST",
                body: JSON.stringify({
                    username: $("#username").value.trim(),
                    password: $("#password").value
                })
            });
            $("#password").value = "";
            displaySession(result.data, result.data.csrfToken);
        });
    });
    $("#logout-button").addEventListener("click", () => run(async () => {
        await api("/api/v1/auth/logout", { method: "POST", body: "{}" });
        resetSession();
        showNotice("You have signed out.", "success");
    }));
    $("#import-key-button").addEventListener("click", () => run(importPrivateKey));
    $("#event-type").addEventListener("change", (event) => defaultDataFor(event.target.value));
    $("#event-form").addEventListener("submit", (event) => {
        event.preventDefault();
        runWithButton(event.submitter, submitBatchEvent);
    });
    $("#shipment-form").addEventListener("submit", (event) => {
        event.preventDefault();
        runWithButton(event.submitter, submitShipmentProposal);
    });
    $("#refresh-inbox").addEventListener("click", () => run(refreshInbox));
    $("#trace-form").addEventListener("submit", (event) => {
        event.preventDefault();
        run(() => loadPublicTrace($("#trace-batch-code").value));
    });
    $("#transaction-form").addEventListener("submit", (event) => {
        event.preventDefault();
        runWithButton(event.submitter, () => startStatusTracking($("#transaction-id").value.trim()));
    });
    document.querySelectorAll(".nav-item").forEach((button) => {
        button.addEventListener("click", () => activateView(button.dataset.view));
    });

    $("#shipment-expires").value = new Date(Date.now() + 60 * 60 * 1000)
        .toISOString().slice(0, 16);
    defaultDataFor("HARVESTED");
    initialize();
})();
