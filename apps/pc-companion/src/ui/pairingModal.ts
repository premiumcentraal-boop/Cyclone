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
  private inputs: HTMLInputElement[] = [];
  private message = el("div", "pair-message");
  private countdown = el("div", "pair-countdown");
  private submitButton = button("Pair phone", "button primary wide");

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
    this.render();
    await this.begin();
    return this.backdrop;
  }

  close(): void {
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
    const copy = el("p", "pair-copy", "Enter the 4-letter code shown in Cyclone on this phone");
    const boxes = el("div", "pair-code-boxes");
    this.inputs = Array.from({ length: 4 }, (_, index) => {
      const input = el("input", "pair-code-input") as HTMLInputElement;
      input.maxLength = 1;
      input.autocomplete = "off";
      input.autocapitalize = "characters";
      input.spellcheck = false;
      input.inputMode = "text";
      input.setAttribute("aria-label", `Pairing code character ${index + 1}`);
      input.addEventListener("input", () => this.updateFromInput(index, input.value));
      input.addEventListener("keydown", (event) => this.handleKeyDown(event, index));
      input.addEventListener("paste", (event) => this.handlePaste(event));
      boxes.append(input);
      return input;
    });

    this.submitButton.disabled = true;
    this.submitButton.addEventListener("click", () => void this.confirm());
    const retry = button("Get a new code", "button ghost wide");
    retry.addEventListener("click", () => void this.begin());

    this.dialog.replaceChildren(header, title, copy, boxes, this.message, this.countdown, this.submitButton, retry);
  }

  private async begin(): Promise<void> {
    this.message.textContent = "Starting secure pairing…";
    this.message.className = "pair-message";
    this.submitButton.disabled = true;
    this.code = "";
    this.syncInputs();
    try {
      this.pairing = await this.service.pairBegin(this.device.id);
      this.message.textContent = "Code ready";
      this.startCountdown();
      this.inputs[0]?.focus();
    } catch {
      this.pairing = null;
      this.message.textContent = "Pairing isn't available right now. Try again.";
      this.message.classList.add("error");
    }
  }

  private updateFromInput(index: number, raw: string): void {
    const char = normalizePairCode(raw).slice(-1);
    const chars = this.code.padEnd(4, " ").split("");
    chars[index] = char || " ";
    this.code = chars.join("").replace(/ /g, "").slice(0, 4);
    this.syncInputs();
    if (char && index < 3) this.inputs[index + 1]?.focus();
    this.submitButton.disabled = !isPairCodeComplete(this.code) || !this.pairing || this.submitting;
    this.clearInvalidMessage();
  }

  private handleKeyDown(event: KeyboardEvent, index: number): void {
    if (event.key === "Backspace" && !this.inputs[index]?.value && index > 0) {
      event.preventDefault();
      const chars = this.code.padEnd(4, " ").split("");
      chars[index - 1] = " ";
      this.code = chars.join("").replace(/ /g, "").slice(0, 4);
      this.syncInputs();
      this.inputs[index - 1]?.focus();
    }
    if (event.key === "Enter" && isPairCodeComplete(this.code)) {
      event.preventDefault();
      void this.confirm();
    }
  }

  private handlePaste(event: ClipboardEvent): void {
    event.preventDefault();
    const pasted = normalizePairCode(event.clipboardData?.getData("text") ?? "");
    if (!pasted) return;
    this.code = pasted;
    this.syncInputs();
    this.inputs[Math.min(3, pasted.length - 1)]?.focus();
    this.submitButton.disabled = !isPairCodeComplete(this.code) || !this.pairing;
    this.clearInvalidMessage();
  }

  private syncInputs(): void {
    const chars = this.code.split("");
    this.inputs.forEach((input, index) => {
      input.value = chars[index] ?? "";
    });
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
    try {
      const result = await this.service.pairConfirm(this.device.id, this.pairing.pairingId, this.code);
      if (!result.ok) {
        if (result.reason === "INVALID_CODE") this.showError("That code doesn't match. Try again.");
        else if (result.reason === "EXPIRED") this.showError("That code expired. Get a new code.");
        else this.showError(result.message || "Pairing couldn't finish. Crash diagnostics were saved automatically; open Settings & diagnostics.");
        return;
      }
      this.dialog.classList.add("success");
      this.message.textContent = "Phone paired · Gateway health verified";
      this.message.className = "pair-message success";
      window.setTimeout(() => {
        this.onPaired(result.device);
        this.close();
      }, 380);
    } catch {
      this.showError("Pairing couldn't finish. Crash diagnostics were saved automatically; open Settings & diagnostics.");
    } finally {
      this.submitting = false;
      this.submitButton.textContent = "Pair phone";
      this.submitButton.disabled = !isPairCodeComplete(this.code);
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
