# Contributing

Thanks for looking at this. It is a small plugin with one maintainer, so the
rules below exist to keep review time short and releases safe. They take
precedence over the [jenkinsci default guide](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md).

## Before you write code

- Bug: open an issue with the Jenkins and plugin versions, what you expected
  and what happened. A failing test or a reproduction from `mvn hpi:run` is
  the fastest way to get it fixed.
- Anything larger than a bug fix: open an issue first and wait for a reply.
  Design is settled there, not in the PR. Issue #2 is a good example of the
  shape.
- Security problems go to the Jenkins security team, never to a public issue:
  <https://www.jenkins.io/security/#reporting-vulnerabilities>.

## Build, test, run

You need Java 21 and Maven.

```sh
mvn test                                   # full suite, what CI runs
mvn test -Dtest=DoraCalculatorTest         # one class while you iterate
mvn hpi:run                                # http://localhost:8080/jenkins/ with the plugin installed
```

Tests use `jenkins-test-harness`. Each test boots a Jenkins, so expect ten to
twenty seconds per test on a warm machine and a full suite of several minutes.
Reports land in `target/surefire-reports/`.

## What a pull request needs

- One topic per PR, from a branch on your fork.
- Tests for the behaviour you changed. If you cannot test it, say why in the
  description.
- Documentation in the same PR when the change is visible to users: the
  README bullet for an option, and a fresh `docs/configuration.png` when the
  settings page changes.
- Exactly one label. The label writes the release notes:
  `enhancement` or `bug` for changes users should get a release for;
  `documentation`, `maintenance` or `tests` for changes they should not.
- A description that says what changed, why, and how you verified it,
  including numbers you actually saw.
- Commit subjects in conventional style (`feat:`, `fix:`, `docs:`), under 72
  characters, with no trailers.
- All checks green and every review conversation resolved. Both are enforced
  on `main`.

Expect a reply within a few days. If a PR goes quiet on your side for a
month it will be closed; reopen it when you are back.

## How releases happen

Merging does not release. A maintainer runs the `cd` workflow from the
Actions tab when there is something worth shipping. Versions follow the
Jenkins scheme `<count>.v<sha>`, and the release notes come from the PR
titles and labels, which is why both matter.

## Using AI tooling

AI assistance is fine. What is not fine is making a reviewer pay for it.

- Disclose it. The PR template has an `AI assistance:` line; fill it in with
  what the tooling was used for, or `none`. Undisclosed use found during
  review closes the PR.
- Own it. You ran the tests yourself and you can explain every line and every
  test in your own words. "The tool suggested it" is not an answer to a review
  question.
- Earn the review. A change has to be worth more than the time it takes to
  read it. Large speculative sweeps, refactors nobody asked for, and review
  replies pasted from a model are closed without discussion.
- The same applies to issues. Reports that are generated rather than
  observed, or that describe a problem the reporter has not reproduced, are
  closed.

Coding agents working in a checkout should also read [AGENTS.md](AGENTS.md).

## Conduct and license

This project follows the [Jenkins code of conduct](https://www.jenkins.io/project/conduct/).
Contributions are accepted under the [MIT License](LICENSE).
