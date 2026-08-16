# Current Scope: A0

A0 contains one human, agent, model, project, session, ContextSpec, and persistent
SCI Context. It supports project reading through the BB1 `project/read` semantic
operation, normalized REPL/finish actions, a durable structured journal, and
restart recovery by replaying successful forms into a fresh context.

It does not provide a TUI, editing, process authority, hard isolation, transparent
SCI heap serialization, automatic memory, multiple agents, provider routing, or a
general plugin framework.
