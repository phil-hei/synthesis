import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { MappingEditorComponent, ColumnMapping } from './mapping-editor.component';

describe('MappingEditorComponent', () => {
  it('edits arbitrary domain fields and removes stale composition rules when unmapping', async () => {
    await TestBed.configureTestingModule({ imports: [MappingEditorComponent] }).compileComponents();
    const f = TestBed.createComponent(MappingEditorComponent); const mapping: ColumnMapping = { columns: { jobs: 0 }, joins: { jobs: { columns: [0, 1], separator: ' ' } }, fillDownFields: ['jobs'] };
    f.componentRef.setInput('mapping', mapping); f.componentRef.setInput('fields', [['jobs', 'Arbeitsplätze']]); f.componentRef.setInput('columns', [{ index: 0, header: 'jobs' }]); f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Arbeitsplätze'); expect(f.nativeElement.textContent).not.toContain('Tourkennung');
    f.componentInstance.setColumn('jobs', -1); expect(mapping.columns['jobs']).toBeUndefined(); expect(mapping.joins?.['jobs']).toBeUndefined(); expect(mapping.fillDownFields).toEqual([]); f.destroy();
  });
});
