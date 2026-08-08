import { Directive, ElementRef, effect, inject, input } from '@angular/core';

/**
 * Briefly pulses the host background when the bound value changes.
 * Relies on the global `.flash-change` CSS helper (respects prefers-reduced-motion).
 */
@Directive({
  selector: '[appFlashOnChange]',
  standalone: true,
})
export class FlashOnChangeDirective {
  readonly appFlashOnChange = input.required<unknown>();

  private readonly el = inject(ElementRef<HTMLElement>);
  private previous: unknown = undefined;
  private initialized = false;

  constructor() {
    effect(() => {
      const value = this.appFlashOnChange();
      if (!this.initialized) {
        this.initialized = true;
        this.previous = value;
        return;
      }
      if (Object.is(value, this.previous)) {
        return;
      }
      this.previous = value;
      const node = this.el.nativeElement;
      node.classList.remove('flash-change');
      // Force reflow so the animation restarts when the class is re-added.
      void node.offsetWidth;
      node.classList.add('flash-change');
    });
  }
}
