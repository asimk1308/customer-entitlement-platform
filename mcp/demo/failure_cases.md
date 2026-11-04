## Failures found by running a real model

These came from reading real transcripts. All three passed every test at the time: the tools were
correct, but they served a model and its user badly.

### 1. It answered "which customers?" with IDs

The question asks which *customers*, and the first live run answered with accounts:

> | ACC-007 | CS-10231 | Open | 216h (~9 days) | 74 days | Backup failing on node 3 |

The answer was right, but a support person can't use `ACC-007`. The agent could have called
`get_customer_summary` for each account, but that is the kind of extra call the tool exists to avoid.
The rows simply had no name in them.

**Fix:** added `customerName` to the API's case rows and `customer` to `search_cases`, with a test and
a check in this demo. Section 1 above shows the result.

### 2. It listed cases to find one

Asked to escalate CS-10231, the agent called `search_cases` with `status_group: "any", limit: 25` and
looked through the results, because the tool had no lookup by ID. That spent context on 24 unrelated
cases, and it only worked because the case happened to be on the first page. A low-priority case
further down wouldn't have been found this way.

**Fix:** added `case_id` to `search_cases`. A test uses a case that can't appear on the first page.

### 3. It told the user engineers had been alerted

After escalating, the agent said:

> CS-10231 is now escalated. The system confirmed the case status changed to `escalated`, and on-call
> engineers have been alerted.

Nothing confirmed that, and nothing here pages anyone. The agent was repeating my tool description,
which said escalating "alerts on-call engineers". The description was wrong, and the model trusted it.

**Fix:** the description now says what the tool does, and tells the model that it doesn't know who
was notified. A test and a check in this demo guard against the claim returning. It is the most
useful of the three, because tool descriptions are effectively part of the prompt: a wrong one
becomes a confident false statement to a user.
