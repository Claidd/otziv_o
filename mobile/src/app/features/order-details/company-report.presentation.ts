export type CompanyReportFact = {
  label?: string | null;
  value?: string | null;
  evidence?: string | null;
  confidence?: string | null;
};

export type CompanyReportSource = {
  title?: string | null;
  url?: string | null;
  note?: string | null;
  type?: string | null;
  confidence?: string | null;
};

export type CompanyReportSection = {
  id: string;
  title: string;
  body: string;
  html: string;
};

export type CompanyReport = {
  city?: string | null;
  provider?: string | null;
  model?: string | null;
  reportMarkdown?: string | null;
  sections?: Array<{ title?: string | null; body?: string | null }> | null;
  sources?: CompanyReportSource[] | null;
  warnings?: string[] | null;
  reviewIdeas?: string[] | null;
  factSnapshot?: {
    confirmedFacts?: CompanyReportFact[] | null;
    uncertainFacts?: CompanyReportFact[] | null;
  } | null;
  createdAt?: string | null;
};

export class CompanyReportPresentation {
  buildCompanyReportSections(report: CompanyReport | null): CompanyReportSection[] {
    if (!report) {
      return [];
    }

    const explicitSections = (report.sections ?? [])
      .map((section) => ({
        title: this.cleanText(section.title),
        body: this.cleanReportBody(section.body)
      }))
      .filter((section) => section.body)
      .map((section, index) => this.createCompanyReportSection(section.title || `Раздел ${index + 1}`, section.body, index));
    if (explicitSections.length) {
      return explicitSections;
    }

    return this.sectionsFromMarkdown(report.reportMarkdown);
  }

  sectionsFromMarkdown(markdown?: string | null): CompanyReportSection[] {
    const text = this.cleanReportBody(markdown);
    if (!text) {
      return [];
    }

    const sections: CompanyReportSection[] = [];
    let title = 'Кратко';
    let body: string[] = [];
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trimEnd();
      const heading = line.match(/^#{1,4}\s+(.+)$/);
      if (heading) {
        this.pushCompanyReportSection(sections, title, body.join('\n'));
        title = this.cleanMarkdownText(heading[1]);
        body = [];
        continue;
      }
      body.push(line);
    }
    this.pushCompanyReportSection(sections, title, body.join('\n'));

    return sections.length ? sections : [this.createCompanyReportSection('Отчет', text, 0)];
  }

  pushCompanyReportSection(sections: CompanyReportSection[], title: string, body: string): void {
    const cleanBody = this.cleanReportBody(body);
    if (!cleanBody) {
      return;
    }

    sections.push(this.createCompanyReportSection(this.cleanText(title) || `Раздел ${sections.length + 1}`, cleanBody, sections.length));
  }

  createCompanyReportSection(title: string, body: string, index: number): CompanyReportSection {
    const cleanBody = this.cleanReportBody(body);
    const cleanTitle = this.cleanText(title) || 'Раздел';
    return {
      id: `company-report-section-${index}-${this.companyReportSectionSlug(cleanTitle)}`,
      title: cleanTitle,
      body: cleanBody,
      html: this.renderCompanyReportMarkdown(cleanBody)
    };
  }

  companyReportSectionSlug(title: string): string {
    return title
      .toLowerCase()
      .replace(/\s+/g, '-')
      .replace(/[^a-zа-яё0-9-]/g, '')
      .slice(0, 40) || 'section';
  }

  renderCompanyReportMarkdown(markdown?: string | null): string {
    const text = this.cleanReportBody(markdown);
    if (!text) {
      return '';
    }

    const html: string[] = [];
    const paragraph: string[] = [];
    const listItems: string[] = [];
    const tableRows: string[][] = [];
    let listTag: 'ul' | 'ol' | null = null;

    const flushParagraph = () => {
      if (!paragraph.length) {
        return;
      }
      html.push(`<p>${this.renderInlineReportMarkdown(paragraph.join(' '))}</p>`);
      paragraph.length = 0;
    };

    const flushList = () => {
      if (!listTag || !listItems.length) {
        return;
      }
      html.push(`<${listTag}>${listItems.map((item) => `<li>${item}</li>`).join('')}</${listTag}>`);
      listItems.length = 0;
      listTag = null;
    };

    const flushTable = () => {
      if (!tableRows.length) {
        return;
      }

      const separatorIndex = tableRows.findIndex((row) => this.isMarkdownTableSeparator(row));
      const hasHeader = separatorIndex === 1 && tableRows.length > 2;
      const headerRow = hasHeader ? tableRows[0] : null;
      const bodyRows = tableRows
        .filter((row, index) => !this.isMarkdownTableSeparator(row) && (!hasHeader || index !== 0));

      if (!headerRow && bodyRows.length < 2) {
        paragraph.push(...tableRows.map((row) => row.join(' | ')));
      } else {
        const bodyHtml = bodyRows
          .map((row, rowIndex) => {
            const title = this.cleanText(row[0]) || `Строка ${rowIndex + 1}`;
            const tone = `tone-${(rowIndex % 4) + 1}`;
            const fieldsSource = row.length > 1 ? row.slice(1) : row;
            const labelsSource = row.length > 1 ? headerRow?.slice(1) : headerRow;
            const fields = fieldsSource
              .map((cell, index) => {
                const label = labelsSource?.[index] || `Поле ${index + 1}`;
                return `<div class="company-report-table-field"><span class="company-report-table-label">${this.renderInlineReportMarkdown(label)}</span><p class="company-report-table-value">${this.renderInlineReportMarkdown(cell || '-')}</p></div>`;
              })
              .join('');
            if (!fieldsSource.length) {
              return `<article class="company-report-table-card ${tone}"><h5>${this.renderInlineReportMarkdown(title)}</h5></article>`;
            }
            return `<article class="company-report-table-card ${tone}"><h5>${this.renderInlineReportMarkdown(title)}</h5>${fields}</article>`;
          })
          .join('');
        html.push(`<div class="company-report-table-stack">${bodyHtml}</div>`);
      }

      tableRows.length = 0;
    };

    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) {
        flushParagraph();
        flushList();
        flushTable();
        continue;
      }

      if (this.isMarkdownTableLine(line)) {
        flushParagraph();
        flushList();
        tableRows.push(this.parseMarkdownTableRow(line));
        continue;
      }

      flushTable();

      const heading = line.match(/^#{2,5}\s+(.+)$/);
      if (heading) {
        flushParagraph();
        flushList();
        html.push(`<h4>${this.renderInlineReportMarkdown(heading[1])}</h4>`);
        continue;
      }

      if (this.isReportSubheadingLine(line)) {
        flushParagraph();
        flushList();
        html.push(`<h5 class="company-report-subheading">${this.renderInlineReportMarkdown(line.replace(/:$/, ''))}</h5>`);
        continue;
      }

      const unordered = line.match(/^[-*]\s+(.+)$/);
      if (unordered) {
        flushParagraph();
        if (listTag && listTag !== 'ul') {
          flushList();
        }
        listTag = 'ul';
        listItems.push(this.renderInlineReportMarkdown(unordered[1]));
        continue;
      }

      const ordered = line.match(/^\d+[.)]\s+(.+)$/);
      if (ordered) {
        flushParagraph();
        if (listTag && listTag !== 'ol') {
          flushList();
        }
        listTag = 'ol';
        listItems.push(this.renderInlineReportMarkdown(ordered[1]));
        continue;
      }

      flushList();
      paragraph.push(line);
    }

    flushParagraph();
    flushList();
    flushTable();

    return html.join('');
  }

  renderInlineReportMarkdown(value?: string | null): string {
    return this.escapeHtml(this.cleanText(value))
      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
      .replace(/__(.+?)__/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code>$1</code>');
  }

  parseMarkdownTableRow(line: string): string[] {
    return line
      .replace(/^\|/, '')
      .replace(/\|$/, '')
      .split('|')
      .map((cell) => this.cleanText(cell));
  }

  isMarkdownTableLine(line: string): boolean {
    const trimmed = line.trim();
    return trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.includes('|');
  }

  isMarkdownTableSeparator(row: string[]): boolean {
    return row.length > 0 && row.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s/g, '')));
  }

  isReportSubheadingLine(line: string): boolean {
    const text = this.cleanMarkdownText(line);
    return text.endsWith(':') && text.length <= 90 && !text.includes('|');
  }

  escapeHtml(value: string): string {
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  cleanReportBody(value?: string | null): string {
    return this.cleanText(value)
      .replace(/\r\n/g, '\n')
      .replace(/\n{3,}/g, '\n\n');
  }

  cleanMarkdownText(value?: string | null): string {
    return this.cleanText(value)
      .replace(/\*\*(.*?)\*\*/g, '$1')
      .replace(/__(.*?)__/g, '$1')
      .replace(/`([^`]+)`/g, '$1');
  }

  cleanStringList(values?: Array<string | null | undefined> | null): string[] {
    return (values ?? [])
      .map((value) => this.cleanText(value))
      .filter((value, index, items) => !!value && items.indexOf(value) === index);
  }

  cleanText(value?: unknown): string {
    return typeof value === 'string' ? value.trim() : '';
  }
}
