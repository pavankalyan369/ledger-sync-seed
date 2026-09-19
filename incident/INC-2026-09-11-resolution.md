# Incident Resolution — INC-2026-09-11

Incident: Whole-rupee SMS amounts such as `Rs.5` were not matched by the amount parser.
Impact: The parser fell through to the available-balance value, causing a WATER CAN transaction to be recorded as ₹92,213.10 instead of ₹5.00.
Root cause: The amount regex required two decimal places, so valid integer-rupee amounts were skipped.
Fix: Updated amount parsing to accept both whole-rupee and decimal amounts, and corrected the affected V2 fixture seed.
Verification: Added regression coverage for `Rs.5` and `INR 25`; the full test suite and corpus-a verification now pass.