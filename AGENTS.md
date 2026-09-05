# AGENTS.md

Instructions for coding agents working in this checkout. Humans should read
[CONTRIBUTING.md](CONTRIBUTING.md); the rules there apply to you too.

## What this is

A Jenkins plugin that records builds, stages and commits into an embedded
SQLite database and computes the four DORA metrics, pipeline rankings and
exports from it. Java 21, Maven, `jenkins-test-harness`. Package map is in the
README under Architecture.

## Commands

```sh
mvn test                                   # full suite, what CI runs
mvn test -Dtest=DoraCalculatorTest         # one class
mvn test -Dtest='MetricsStoreTest#queriesLeaveExcludedJobsOut'   # one method
mvn hpi:run                                # plugin in a local Jenkins at http://localhost:8080/jenkins/
```

- Test reports: `target/surefire-reports/`. The `.txt` per class has the summary,
  the `-output.txt` has the console.
- `mvn hpi:run` keeps its Jenkins home in `work/` (ignored by git). Reuse it.
- Every JenkinsRule test boots a real Jenkins. Ten to twenty seconds per test
  when the machine is warm, a minute or more when it is not. A slow suite is
  not a failure.
- Run one Maven process per checkout, ever. A second build recompiles into
  `target/classes` while the first test JVM is still loading from it and both
  results become meaningless. Chain steps in one script instead.

## Verification standard

- Reproduce first. A bug gets a failing test before a fix; a feature gets the
  test that would fail without it.
- After the fix, prove the test has teeth: revert or neuter the fix and watch
  the test fail, then restore. A green build alone does not show the changed
  lines ran.
- Check Jenkins semantics against the core version in `pom.xml`
  (`jenkins.baseline`), not against upstream master. Default methods and
  behaviour differ between versions.
- Run the smallest relevant test first, then the full suite before handing
  over. Report the real numbers, including what was not run.

## Repository rules

- A configuration option is five things or it is not done: the field and
  `configure()` in `DoraGlobalConfiguration`, the `f:entry` in `config.jelly`,
  the README bullet, `docs/configuration.png`, and a round trip test that
  submits the real form (`JenkinsRule.configRoundtrip()`). Jelly renders
  fields as `_.name`.
- Queries take bind variables, never interpolated strings. Aggregates live in
  `MetricsStore`; calculators and rankers do not build SQL.
- Never put real job names, hosts, cluster or company identifiers in code,
  tests, fixtures, screenshots or docs. Use plain synthetic names.
- Do not touch `target/`, `work/`, the SQLite schema without a migration note,
  or the release trigger in `.github/workflows/cd.yaml`.
- Screenshots in `docs/` are 3568x1824. Retake the affected one when the UI
  it shows changes.

## Commits and pull requests

- Subject in conventional style (`feat:`, `fix:`, `chore:`, `docs:`), under 72
  characters. Body says why, not what the diff already shows.
- No trailers of any kind. No `@mentions`, no `fixes #` in commit messages;
  the PR description closes issues.
- Every PR carries exactly one release-drafter label. The label writes the
  release notes and decides whether the change is release-worthy.
- Merging never publishes a release. A maintainer runs the `cd` workflow by
  hand.

## What you do not do

- You do not post. PR descriptions, issue replies and review responses are
  written and sent by the human maintainer. Draft locally if asked.
- You do not open PRs, add labels, merge, or run the release workflow.
- You do not claim a test ran, a check passed or a behaviour holds unless you
  saw the output in this session.
