"""mailsearch — a private, local search engine for an Outlook mailbox.

Mail is fetched once over Microsoft Graph and indexed into SQLite FTS5 on this
machine.  Searching never leaves the machine, and nothing is sent anywhere
except to Microsoft, to read the mailbox the user signed in to.
"""

__version__ = "0.1.0"

__all__ = ["__version__"]
