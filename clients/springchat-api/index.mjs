// @springchat/api for ES modules
export class SpringChatError extends Error {
  constructor(status, message) {
    super(message);
    this.name = "SpringChatError";
    this.status = status;
  }
}

export class SpringChatPlugin {
  constructor({ baseUrl = "http://localhost:8080", key } = {}) {
    if (!key) throw new Error("A plugin key is required");
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this.key = key;
  }

  async _request(path, options = {}) {
    const res = await fetch(this.baseUrl + path, {
      ...options,
      headers: {
        "Content-Type": "application/json",
        "X-Plugin-Key": this.key,
        ...(options.headers || {})
      }
    });
    const text = await res.text();
    if (!res.ok) throw new SpringChatError(res.status, text || res.statusText);
    return text ? JSON.parse(text) : null;
  }

  /** Returns { name, groupId, group } for this plugin's key. */
  info() {
    return this._request("/api/plugin/info");
  }

  /** Posts a text message (up to 2000 characters) and returns the stored message. */
  postMessage(text) {
    return this._request("/api/plugin/messages", { method: "POST", body: JSON.stringify({ text }) });
  }
}

export default SpringChatPlugin;
