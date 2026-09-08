import { Component, Input } from '@angular/core';

let nextHelpId = 0;

/** Kurze Zusatzhilfe, erreichbar mit Maus, Tastatur und Touch. */
@Component({
  selector: 'app-help-tip',
  template: `
    <span class="help" (mouseenter)="hovered = true; dismissed = false" (mouseleave)="hovered = false">
      <button type="button" [attr.aria-label]="label" [attr.aria-describedby]="helpId"
        [attr.aria-expanded]="visible" (focus)="focused = true; dismissed = false"
        (blur)="focused = false; pinned = false" (click)="pinned = !pinned; dismissed = !pinned"
        (keydown.escape)="dismissed = true; pinned = false">ⓘ</button>
      <span [id]="helpId" role="tooltip" [class.visible]="visible">{{ text }}</span>
    </span>
  `,
  styles: `
    :host { display: block; margin-top: 3px; }
    .help { position: relative; display: inline-flex; }
    button { border: 0; border-radius: 50%; background: #edf3f9; color: #2b567c;
      width: 24px; height: 24px; padding: 0; font-size: 16px; cursor: help; }
    button:focus-visible { outline: 2px solid #2f6fb1; outline-offset: 2px; }
    [role="tooltip"] { display: none; position: absolute; z-index: 30; top: 100%; left: 0;
      width: 260px; max-width: 70vw; padding: 11px 13px; box-sizing: border-box;
      border: 1px solid #cad8e6; border-radius: 8px; background: #15314f; color: #fff;
      box-shadow: 0 4px 16px #15314f26; font: 12px/1.5 system-ui, sans-serif;
      text-align: left; text-transform: none; letter-spacing: normal; white-space: normal; }
    [role="tooltip"].visible { display: block; }
  `
})
export class HelpTipComponent {
  @Input({ required: true }) text = '';
  @Input({ required: true }) label = '';
  readonly helpId = `studio-help-${nextHelpId++}`;
  hovered = false;
  focused = false;
  pinned = false;
  dismissed = false;

  get visible(): boolean {
    return !this.dismissed && (this.hovered || this.focused || this.pinned);
  }
}
