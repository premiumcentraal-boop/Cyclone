import QRCode from "qrcode";
import { normalizePairCode, pairSecondsRemaining, pairSubmissionState } from "../core/pairing.js";
import type { DesktopDevice, DesktopService, PairBeginResult } from "../services/types.js";
import { button, el } from "./dom.js";

export class PairingModal {
  private readonly backdrop = el("div", "modal-backdrop");
  private readonly dialog = el("section", "pairing-modal");
  private code = "";
  private pairing: PairBeginResult | null = null;
  private countdownTimer: number | null = null;
  private qrPollTimer: number | null = null;
  private qrPolling = false;
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
  private qrPanel = el("div", "pair-qr-panel");
  private qrCanvas = el("canvas", "pair-qr-canvas") as HTMLCanvasElement;
  private qrStatus = el("div", "pair-qr-status", "Preparing secure QR…");

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
    this.stopQrPolling();
    this.backdrop.remove();
    this.code = "";
    this.onClose();
  }

  private render(): void {
    const header = el("div", "pair-header");
    const close = button("×", "icon-button");
    close.setAttribute("aria-label", "Close pairing");
    close.addEventListener("click", () => this.close());
    const identity = el("div", "pair-device-name", this.device.name);
    identity.setAttribute("title", `Cyclone PC Companion ${__CYCLONE_PC_VERSION__}`);
    header.append(identity, close);

    const title = el("h2", "pair-title", "Pair this phone");
    const copy = el("p", "pair-copy", "Scan the QR with this phone's camera or Cyclone › PC Gateway, or enter the 4-letter code shown on the phone.");
    this.qrPanel.append(this.qrCanvas, this.qrStatus);
    this.qrPanel.hidden = true;
    const divider = el("div", "pair-divider", "or enter the phone code");
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
      if (event.key === "Enter" && this.currentSubmissionState().ready) {
        event.preventDefault();
        void this.confirm();
      }
    });
    field.append(this.input);

    this.submitButton.disabled = true;
    this.input.disabled = true;
    this.submitButton.addEventListener("click", () => void this.confirm());
    this.retryButton.addEventListener("click", () => void this.begin());

    this.dialog.replaceChildren(header, title, copy, this.qrPanel, divider, field, this.message, this.countdown, this.diagnostics, this.submitButton, this.retryButton);
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
    if (this.input) {
      this.input.value = "";
      this.input.disabled = true;
    }
    if (this.countdownTimer != null) window.clearInterval(this.countdownTimer);
    this.countdownTimer = null;
    this.stopQrPolling();
    this.countdown.textContent = "";
    try {
      const pairing = await this.service.pairBegin(this.device.id);
      if (this.closed || sequence !== this.beginSequence) return;
      if (!pairing.pairingId.trim() || !Number.isFinite(pairing.expiresAtEpochMs)
        || pairSecondsRemaining(pairing.expiresAtEpochMs, Date.now()) === 0) {
        throw new Error("Invalid pairing challenge");
      }
      this.pairing = pairing;
      if (this.input) this.input.disabled = false;
      if (this.pairing.diagnosticsActive) {
        this.diagnostics.textContent = "● Live crash monitor active before pairing";
        if (this.pairing.diagnosticsPath) this.diagnostics.setAttribute("title", this.pairing.diagnosticsPath);
      } else {
        this.diagnostics.textContent = "Live monitor unavailable · fixed crash capture will still run on failure";
      }
      this.startCountdown();
      this.syncSubmissionUi();
      void this.renderQr(pairing);
      this.startQrPolling();
      this.input?.focus();
    } catch {
      if (this.closed || sequence !== this.beginSequence) return;
      this.pairing = null;
      this.qrPanel.hidden = true;
      this.message.textContent = "Cyclone could not create a pairing challenge. Select Get a new code to retry.";
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
    this.syncSubmissionUi(true);
  }

  private async confirm(): Promise<void> {
    const state = this.currentSubmissionState();
    if (!state.ready) {
      if (state.reason === "EXPIRED") {
        this.showError("That code expired. Get a new code and try again.");
      } else if (state.reason === "NO_CHALLENGE") {
        this.showError("Pairing is not ready. Select Get a new code before entering the phone code.");
      } else if (state.reason === "INCOMPLETE_CODE") {
        this.showError("Enter all 4 letters shown on the phone.");
      }
      return;
    }
    const pairing = this.pairing;
    if (!pairing) return;
    if (pairSecondsRemaining(pairing.expiresAtEpochMs, Date.now()) === 0) {
      this.showError("That code expired. Get a new code and try again.");
      return;
    }
    this.submitting = true;
    this.submitButton.disabled = true;
    this.submitButton.textContent = "Pairing…";
    if (pairing.diagnosticsActive) this.diagnostics.textContent = "● Recording pairing transition and Cyclone process health…";
    try {
      const result = await this.service.pairConfirm(this.device.id, pairing.pairingId, this.code);
      if (!result.ok) {
        if (result.reason === "EXPIRED" || result.reason === "STALE_CODE") this.pairing = null;
        if (result.reason === "INVALID_CODE") this.showError("That code doesn't match. Try again.");
        else if (result.reason === "EXPIRED") this.showError("That code expired. Get a new code.");
        else if (result.reason === "STALE_CODE") this.showError("A newer code was requested. Get a new code and enter the latest one.");
        else this.showError(result.message || "Pairing couldn't finish. The live Android session and crash snapshot were saved; open Settings & diagnostics.");
        return;
      }
      this.finishPairing(result.device);
    } catch {
      this.showError("Pairing couldn't finish. The live Android session and crash snapshot were saved; open Settings & diagnostics.");
    } finally {
      this.submitting = false;
      this.submitButton.textContent = "Pair phone";
      this.syncSubmissionUi();
    }
  }

  private startCountdown(): void {
    if (this.countdownTimer != null) window.clearInterval(this.countdownTimer);
    const tick = () => {
      if (!this.pairing) return;
      const remaining = pairSecondsRemaining(this.pairing.expiresAtEpochMs, Date.now());
      this.countdown.textContent = remaining > 0 ? `Code expires in ${remaining}s` : "Code expired";
      this.syncSubmissionUi();
      if (remaining === 0) {
        this.stopQrPolling();
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

  private currentSubmissionState() {
    return pairSubmissionState(
      this.code,
      Boolean(this.pairing?.pairingId.trim()),
      this.submitting,
      this.pairing?.expiresAtEpochMs ?? 0,
      Date.now(),
    );
  }

  private syncSubmissionUi(clearInputError = false): void {
    const state = this.currentSubmissionState();
    this.submitButton.disabled = this.closed || !state.ready;
    this.submitButton.setAttribute("aria-disabled", String(this.submitButton.disabled));
    this.submitButton.title = state.ready
      ? "Pair this phone now"
      : state.reason === "NO_CHALLENGE"
        ? "Request a live pairing code first"
        : state.reason === "EXPIRED"
          ? "This code expired; request a new one"
          : state.reason === "INCOMPLETE_CODE"
            ? "Enter all 4 letters"
            : "Pairing is already in progress";

    if (this.message.classList.contains("error") && !clearInputError) return;
    this.message.className = "pair-message";
    if (state.reason === "READY") {
      this.message.textContent = "Ready to pair · select Pair phone or press Enter";
    } else if (state.reason === "INCOMPLETE_CODE") {
      this.message.textContent = this.pairing?.qrAvailable
        ? "Scan the QR or enter all 4 letters"
        : "Enter all 4 letters shown on the phone";
    } else if (state.reason === "EXPIRED") {
      this.message.textContent = "Code expired · select Get a new code";
      this.message.classList.add("error");
    }
  }

  private async renderQr(pairing: PairBeginResult): Promise<void> {
    if (!pairing.qrAvailable || !pairing.qrPayload) {
      this.qrPanel.hidden = true;
      return;
    }
    this.qrPanel.hidden = false;
    this.qrStatus.textContent = "Scan with camera or Cyclone › PC Gateway";
    try {
      await QRCode.toCanvas(this.qrCanvas, pairing.qrPayload, {
        width: 168,
        margin: 1,
        errorCorrectionLevel: "M",
        color: { dark: "#11131b", light: "#ffffff" },
      });
      if (this.closed || this.pairing?.pairingId !== pairing.pairingId) return;
      this.qrStatus.textContent = "Scan with camera or Cyclone › PC Gateway · pairing completes automatically";
    } catch {
      this.qrPanel.hidden = true;
    }
  }

  private startQrPolling(): void {
    this.stopQrPolling();
    if (!this.pairing?.qrAvailable) return;
    const poll = async () => {
      const pairing = this.pairing;
      if (this.closed || !pairing || this.submitting || this.qrPolling) return;
      this.qrPolling = true;
      try {
        const result = await this.service.pairQrConfirm(this.device.id, pairing.pairingId);
        if (this.closed || this.pairing?.pairingId !== pairing.pairingId) return;
        if (result.ok) {
          this.finishPairing(result.device);
          return;
        }
        if (!result.pending) {
          this.stopQrPolling();
          if (result.reason === "EXPIRED" || result.reason === "STALE_CODE") this.pairing = null;
          this.showError(result.reason === "EXPIRED"
            ? "That QR code expired. Get a new code."
            : result.message || "QR pairing paused. Enter the phone code or request a new challenge.");
          this.syncSubmissionUi();
        }
      } catch {
        // A transient poll failure must never disable the manual-code fallback.
      } finally {
        this.qrPolling = false;
      }
    };
    this.qrPollTimer = window.setInterval(() => void poll(), 900);
  }

  private stopQrPolling(): void {
    if (this.qrPollTimer != null) window.clearInterval(this.qrPollTimer);
    this.qrPollTimer = null;
  }

  private finishPairing(device: DesktopDevice): void {
    this.submitting = true;
    this.pairing = null;
    this.stopQrPolling();
    this.submitButton.disabled = true;
    if (this.input) this.input.disabled = true;
    this.dialog.classList.add("success");
    this.message.textContent = "Phone paired · Gateway health verified";
    this.message.className = "pair-message success";
    this.diagnostics.textContent = "Live monitor remains active while this USB phone is connected.";
    window.setTimeout(() => {
      this.onPaired(device);
      this.close();
    }, 380);
  }
}
