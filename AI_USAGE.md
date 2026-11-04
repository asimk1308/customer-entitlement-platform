# AI usage

## What I used it for
I used an AI coding assistant throughout: researching the role and the current MCP spec, planning, writing the code and tests, and drafting these documents.

**My part:** I set the direction, had the first Python version ported to Java, and asked for the code review whose fixes are included.

**Total time:** about a day of hands-on work.

## What it got wrong
- **The language.** It said this machine had no Java runtime after checking only the `/usr/bin/java` stub, and never asked what I work in. The first version was in Python, so I had it ported to Java, leaving only the MCP server in Python.
- **False claims.** "Token-efficient" was untrue until measured: tool output was larger than the raw API on a full page. "CI blocks breaking changes" was written before any such check existed. A tool description said escalation "alerts on-call engineers", and a real model repeated that to a user (`mcp/demo/failure_cases.md`).
- **Bugs.** An unsatisfiable `allOf` schema passed two validators, and an exception handler turned malformed requests into 404s.
- **Tests that proved too little.** A fuzz run "passed" while its requests mostly got 429s, fuzzing can't see an auth bypass, and a scope test passed because a YAML parser had left it with no scopes to try.
- **"Ready" too early.** It called the work ready with every test passing. A later code review then reproduced failure-path bugs, including one hung feed queueing requests for 17 seconds and a retry committing an escalation twice.

## How I verified its output
- **Mutation testing.** I had controls broken on purpose to confirm that a test failed: 57 on the Java API and mocks, which exposed 2 gaps that now have tests. These runs aren't in the repo.
- **Independent checks.** Schemathesis runs against real processes, the webhook receiver shares no code with the sender, and the sample question's answer is stated by hand.
- **Real conditions.** Faults were injected on real sockets, and I read real model transcripts rather than trusting passing checks.
- **Tests that fail first.** The review's fixes have regression tests, each run against the unfixed code to confirm it failed.
