import { isPairCodeComplete, normalizePairCode, pairSecondsRemaining } from "../core/pairing.js";
import type { DesktopDevice, DesktopService, PairBeginResult } from "../services/types.js";
import { button, el } from "./dom.js";

export class PairingModal {
  private readonly backdrop = el("div", "modal-backdrop");
  private readonly dialog = el("section", "pairing-modal");
  private code = "";
  private pairing: PairBeginResult | null = null;
  private countdownTimer: number | null = null;
  private submitting = false;
  private beginning = false;
  private closed = false;
  private beginSequence = 0;
  private input: HTMLInputElement | null = null;
  private message = el("div", "pair-message");
  private countdown = el("div", "pair-countdown");
  private diagnostics = el("div", "pair-countdown");
  private submitButton = button("Pair phone", "button primary wide");
  private retryButton = button("Get a new code", "button ghost wide");

  constructor(
    private readonly service: DesktopService,
    private readonly device: DesktopDevice,
    private readonly onPaired: (device: DesktopDevice) => void,
    private readonly onClose: () => void,
  ) {
    this.backdrop.append(this.dialog);
    this.backdrop.addEventListener("click", (event) => {
      if (event.target === this.backdrop) this.close();
    });
  }

  async open(): Promise<HTMLElement> {
    this.closed = false;
    this.render();
    // Show the dialog immediately. The phone can create its code while the user sees progress,
    // and focus is applied only after the input has been attached to the document.
    void this.begin();
    return this.backdrop;
  }

  close(): void {
    this.closed = true;
    this.beginSequence += 1;
    if (this.countdownTimer != null) window.clearInterval(this.countdownTimer);
    this.countdownTimer = null;
    this.backdrop.remove();
    this.code = "";
    this.onClose();
  }

  private render(): void {
    const header = el("div", "pair-header");
    const close = button("×", "icon-button");
    close.setAttribute("aria-label", "Close pairing");
    close.addEventListener("click", () => this.close());
    header.append(el("div", "pair-device-name", this.device.name), close);

    const title = el("h2", "pair-title", "Pair this phone");
    const copy = el("p", "pair-copy", "Type or paste the 4-letter code shown in Cyclone on this phone. Everything stays inside this window.");
    const field = el("div", "pair-code-field");
    this.input = el("input", "pair-code-input") as HTMLInputElement;
    // Keep enough room for a formatted paste such as "N O-V A"; normalize immediately.
    this.input.maxLength = 32;
    this.input.autocomplete = "one-time-code";
    this.input.autocapitalize = "characters";
    this.input.spellcheck = false;
    this.input.inputMode = "text";
    this.input.placeholder = "ABCD";
    this.input.setAttribute("aria-label", "Four-letter phone pairing code");
    this.input.addEventListener("input", () => this.updateCode(this.input?.value ?? ""));
    this.input.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && isPairCodeComplete(this.code)) {
        event.preventDefault();
        void this.confirm();
      }
    });
    field.append(this.input);

    this.submitButton.disabled = true;
    this.submitButton.addEventListener("click", () => void this.confirm());
    this.retryButton.addEventListener("click", () => void this.begin());

    this.dialog.replaceChildren(header, title, copy, field, this.message, this.countdown, this.diagnostics, this.submitButton, this.retryButton);
  }

  private async begin(): Promise<void> {
    if (this.closed || this.beginning) return;
    this.beginning = true;
    const sequence = ++this.beginSequence;
    this.message.textContent = "Starting secure pairing…";
    this.message.className = "pair-message";
    this.diagnostics.textContent = "Checking live Android diagnostics…";
    this.submitButton.disabled = true;
    this.retryButton.disabled = true;
    this.pairing = null;
    this.code = "";
    if (this.input) this.input.value = "";
    if (this.countdownTimer != null) window.clearInterval(this.countdownTimer);
    this.countdownTimer = null;
    this.countdown.textContent = "";
    try {
      const pairing = await this.service.pairBegin(this.device.id);
      if (this.closed || sequence !== this.beginSequence) return;
      this.pairing = pairing;
      this.message.textContent = "Code ready";
      if (this.pairing.diagnosticsActive) {
        this.diagnostics.textContent = "● Live crash monitor active before pairing";
        if (this.pairing.diagnosticsPath) this.diagnostics.setAttribute("title", this.pairing.diagnosticsPath);
      } else {
        this.diagnostics.textContent = "Live monitor unavailable · fixed crash capture will still run on failure";
      }
      this.startCountdown();
      this.input?.focus();
    } catch {
      if (this.closed || sequence !== this.beginSequence) return;
      this.pairing = null;
      this.message.textContent = "Pairing isn't available right now. Try again.";
      this.message.classList.add("error");
      this.diagnostics.textContent = "Open Settings & diagnostics to inspect the USB monitor.";
    } finally {
      if (!this.closed && sequence === this.beginSequence) {
        this.beginning = false;
        this.retryButton.disabled = false;
      }
    }
  }

  private updateCode(raw: string): void {
    this.code = normalizePairCode(raw);
    if (this.input && this.input.value !== this.code) {
      this.input.value = this.code;
      this.input.setSelectionRange(this.code.length, this.code.length);
    }
    this.submitButton.disabled = !isPairCodeComplete(this.code) || !this.pairing || this.submitting;
    this.clearInvalidMessage();
  }

  private async confirm(): Promise<void> {
    if (!this.pairing || !isPairCodeComplete(this.code) || this.submitting) return;
    if (pairSecondsRemaining(this.pairing.expiresAtEpochMs, Date.now()) === 0) {
      this.showError("That code expired. Get a new code and try again.");
      return;
    }
    this.submitting = true;
    this.submitButton.disabled = true;
    this.submitButton.textContent = "Pairing…";
    if (this.pairing.diagnosticsActive) this.diagnostics.textContent = "● Recording pairing transition and Cyclone process health…";
    try {
      const result = await this.service.pairConfirm(this.device.id, this.pairing.pairingId, this.code);
      if (!result.ok) {
        if (result.reason === "EXPIRED" || result.reason === "STALE_CODE") this.pairing = null;
        if (result.reason === "INVALID_CODE") this.showError("That code doesn't match. Try again.");
        else if (result.reason === "EXPIRED") this.showError("That code expired. Get a new code.");
        else if (result.reason === "STALE_CODE") this.showError("A newer code was requested. Get a new code and enter the latest one.");
        else this.showError(result.message || "Pairing couldn't finish. The live Android session and crash snapshot were saved; open Settings & diagnostics.");
        return;
      }
      this.dialog.classList.add("success");
      this.message.textContent = "Phone paired · Gateway health verified";
      this.message.className = "pair-message success";
      this.diagnostics.textContent = "Live monitor remains active while this USB phone is connected.";
      window.setTimeout(() => {
        this.onPaired(result.device);
        this.close();
      }, 380);
    } catch {
      this.showError("Pairing couldn't finish. The live Android session and crash snapshot were saved; open Settings & diagnostics.");
    } finally {
      this.submitting = false;
      this.submitButton.textContent = "Pair phone";
      this.submitButton.disabled = this.closed || !this.pairing || !isPairCodeComplete(this.code)
        || pairSecondsRemaining(this.pairing.expiresAtEpochMs, Date.now()) === 0;
    }
  }

  private startCountdown(): void {
    if (this.countdownTimer != null) window.clearInterval(this.countdownTimer);
    const tick = () => {
      if (!this.pairing) return;
      const remaining = pairSecondsRemaining(this.pairing.expiresAtEpochMs, Date.now());
      this.countdown.textContent = remaining > 0 ? `Code expires in ${remaining}s` : "Code expired";
      if (remaining === 0) {
        this.submitButton.disabled = true;
        if (this.countdownTimer != null) window.clearInterval(this.countdownTimer);
        this.countdownTimer = null;
      }
    };
    tick();
    this.countdownTimer = window.setInterval(tick, 1000);
  }

  private showError(message: string): void {
    this.message.textContent = message;
    this.message.className = "pair-message error";
  }

  private clearInvalidMessage(): void {
    if (this.message.classList.contains("error")) {
      this.message.textContent = "Code ready";
      this.message.className = "pair-message";
    }
  }
}
