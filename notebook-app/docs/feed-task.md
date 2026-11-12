# The Notebook Feed (scheduled task, to be created once the repository exists)

Two runs a day copy the Day Book's finished page into the notebook's repository:

- **7:45 PM** New York — tomorrow's page, written by the Evening Setdown at 6:54 PM.
- **4:25 AM** New York — today's page, after the 3:45 AM morning top-up.

Each run, with the GitHub connector and ArtifactData:

1. `ArtifactData get` on the Day Book artifact, collection `days`, doc_id `<date>`, with `out_dir`.
2. Take the document's `content` object only, unchanged, as JSON.
3. `get_file_contents` on `days/<date>/content.json` in the notebook repository to learn its sha (404 means new).
4. `create_or_update_file` with that JSON, message "Day Book: <date>", branch `main`.
5. If `days/<date>/page.json` exists, read its `checks` map and mirror ticks back into the Day Book's
   `user.checks` for that day (future step; not in the first version).

Model: Haiku. The task reads one document and writes one file; it must not change the Day Book.
