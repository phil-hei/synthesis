import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

export interface ColumnMapping {
  columns: Record<string, number>;
  joins?: Record<string, { columns: number[]; separator: string }>;
  fillDownFields?: string[];
}

/** Same column editor for tour imports and schema-driven aspect imports. */
@Component({
  selector: 'app-mapping-editor',
  imports: [FormsModule],
  template: `
    <div class="mapping-fields">
      @for (field of fields; track field[0]) {
        <label>{{ field[1] }}
          <select [ngModel]="column(field[0])" [disabled]="disabled" (ngModelChange)="setColumn(field[0], $event)">
            <option [ngValue]="-1">Nicht zugeordnet</option>
            @for (column of columns; track column.index) {
              <option [ngValue]="column.index">{{ column.index + 1 }}: {{ column.header || '(ohne Überschrift)' }}</option>
            }
          </select>
        </label>
        @if (allowComposition && mapping.columns[field[0]] !== undefined) {
          <label>{{ field[1] }} · Zusatzspalte
            <select [ngModel]="mapping.joins?.[field[0]]?.columns?.[1] ?? -1" [disabled]="disabled" (ngModelChange)="join(field[0], $event)">
              <option [ngValue]="-1">Nicht zusammenführen</option>
              @for (column of columns; track column.index) { <option [ngValue]="column.index">{{ column.index + 1 }}: {{ column.header }}</option> }
            </select>
          </label>
          <label class="check"><input type="checkbox" [checked]="mapping.fillDownFields?.includes(field[0]) ?? false" [disabled]="disabled" (change)="fill(field[0], $any($event.target).checked)">Leere Werte für {{ field[1] }} fortführen</label>
        }
      }
    </div>`,
  styles: [`:host { display:block;grid-column:1/-1;min-width:0; } .mapping-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;} label{display:flex;flex-direction:column;gap:5px;min-width:0;}select{font:inherit;padding:8px;max-width:100%;border:1px solid #cbd5e1;border-radius:5px;background:#fff;color:#18354e;}.check{display:flex;flex-direction:row;align-items:center;}@media(max-width:600px){.mapping-fields{grid-template-columns:1fr;}}`]
})
export class MappingEditorComponent {
  @Input({ required: true }) mapping!: ColumnMapping;
  @Input() fields: string[][] = [];
  @Input() columns: { index: number; header: string }[] = [];
  @Input() allowComposition = false;
  @Input() disabled = false;
  @Output() changed = new EventEmitter<void>();
  column(field: string): number { return this.mapping.columns[field] === undefined ? -1 : this.mapping.columns[field]; }
  setColumn(field: string, raw: unknown): void {
    const index = Number(raw);
    if (index < 0) { delete this.mapping.columns[field]; delete this.mapping.joins?.[field]; this.mapping.fillDownFields = this.mapping.fillDownFields?.filter(f => f !== field); }
    else { this.mapping.columns[field] = index; const join = this.mapping.joins?.[field]; if (join) join.columns[0] = index; }
    this.changed.emit();
  }
  join(field: string, raw: unknown): void {
    const index = Number(raw); this.mapping.joins ??= {};
    if (index < 0) delete this.mapping.joins[field];
    else this.mapping.joins[field] = { columns: [this.mapping.columns[field], index], separator: ' ' };
    this.changed.emit();
  }
  fill(field: string, enabled: boolean): void {
    this.mapping.fillDownFields = (this.mapping.fillDownFields ?? []).filter(f => f !== field);
    if (enabled) this.mapping.fillDownFields.push(field);
    this.changed.emit();
  }
}
