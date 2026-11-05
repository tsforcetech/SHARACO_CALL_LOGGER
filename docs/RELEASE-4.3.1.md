# Caller Pro 4.3.1 development update

- Web import selectors, targets, call history and performance reports resolve staff names by matching the employee record's `uid` to the membership ID. Google profile names are a fallback only.
- New browser imports assign balanced consecutive blocks to employees in selection order. The preview shows each row's assigned employee. Existing assignments and outcomes are preserved.
- New leads retain their import batch, batch start and original row number. Both clients sort by that sequence. Older imports fall back to creation time (the old sequential importer created rows one at a time); historical uploads with equal timestamps cannot recover their original file order.
- Pending means an unworked lead: no outcome, attempt or contact timestamp, and not closed/do-not-call. Saving any outcome moves it to Updated. Follow-up records and call reports remain available. Reopening an eligible updated lead is an explicit action.
- Save & Next selects the next pending lead assigned to the signed-in staff member. Failed saves preserve the current customer and form. Automatic advancement does not initiate a phone call.
- Web and Android use larger status buttons. Android provides a date/time picker. The web dialog traps keyboard focus and supports Escape when no save is running.
- Lead listeners fetch the authorized scope and sort before 50-row presentation pagination, avoiding incorrect ordering at old 50/200-record boundaries. They stop with an explicit error at 10,000 records rather than expose a partial queue. Initial reads scale with this scope; web saves reuse live data rather than re-fetching the whole queue.

No Firebase rules, indexes, billing plan or authentication/signing configuration changed. Existing APK reports are preserved. Real-account staff names and phone behavior still require acceptance testing.

Validation: TypeScript/static web build; web regression tests for names, contiguous assignment, queue order and pending eligibility; Android queue regression tests; CI Android build/tests/lint and existing security suites. A browser fixture exercised pending -> save -> next -> updated and inspected the actual web dialog at 1440px and 390px widths without touching production data.
