from datetime import datetime, timedelta, timezone

from mailsearch import query as q


def parse(text: str, **kwargs):
    return q.parse(text, **kwargs)


def test_plain_words_are_anded_and_quoted():
    parsed = parse("invoice acme ")
    assert parsed.fts == '"invoice" AND "acme"'
    assert parsed.terms == ["invoice", "acme"]
    assert parsed.effective_sort == q.SORT_RELEVANCE


def test_last_word_is_prefix_matched_while_typing():
    assert parse("invo").fts == '"invo"*'
    # A trailing space means the word is finished, so match it whole.
    assert parse("invo ").fts == '"invo"'


def test_explicit_star_is_a_prefix_search():
    assert parse("tax* return ").fts == '"tax"* AND "return"'


def test_quoted_text_is_a_phrase():
    parsed = parse('"purchase order" acme ')
    assert parsed.fts == '"purchase order" AND "acme"'


def test_quotes_inside_a_term_are_escaped_not_injected():
    parsed = parse('say"hi ')
    assert parsed.fts == '"say""hi"'


def test_negation_becomes_an_fts_not():
    parsed = parse("invoice -spam ")
    assert parsed.fts == '("invoice") NOT ("spam")'


def test_or_groups_the_neighbouring_terms():
    parsed = parse("invoice OR receipt ")
    assert parsed.fts == '("invoice" OR "receipt")'


def test_negation_only_query_uses_the_negative_channel():
    parsed = parse("-newsletter ")
    assert parsed.fts == ""
    assert parsed.negative_fts == '"newsletter"'


def test_sender_field_maps_to_the_sender_column():
    parsed = parse("from:alice invoice ")
    assert 'sender:"alice"*' in parsed.fts
    assert '"invoice"' in parsed.fts


def test_quoted_field_value_is_matched_whole():
    assert parse('from:"alice adams" ').fts == 'sender:"alice adams"'


def test_subject_and_recipient_fields():
    assert parse("subject:renewal ").fts == 'subject:"renewal"*'
    assert parse("to:bob ").fts == 'recipients:"bob"*'


def test_has_attachment_is_a_sql_filter_not_text():
    parsed = parse("has:attachment ")
    assert parsed.fts == ""
    assert parsed.where == ["m.has_attachments = 1"]
    assert parsed.effective_sort == q.SORT_NEWEST


def test_is_unread_and_is_important():
    assert parse("is:unread ").where == ["m.is_read = 0"]
    assert parse("is:important ").where == ["LOWER(m.importance) = 'high'"]


def test_folder_filter_also_opens_up_junk_and_deleted():
    parsed = parse("folder:Archive ")
    assert parsed.include_all is True
    assert parsed.params == ["archive", "%archive%"]


def test_absolute_date_filters():
    now = datetime(2026, 5, 10, 12, 0, tzinfo=timezone.utc)
    after = parse("after:2026-05-01 ", now=now)
    assert after.where == ["m.received_ts >= ?"]
    assert after.params == [int(datetime(2026, 5, 1, tzinfo=timezone.utc).timestamp())]

    before = parse("before:2026-05-01 ", now=now)
    # "before the 1st" includes everything up to the end of that day.
    assert before.params == [int(datetime(2026, 5, 2, tzinfo=timezone.utc).timestamp())]


def test_month_and_year_granularity():
    now = datetime(2026, 5, 10, tzinfo=timezone.utc)
    assert parse("after:2026-03 ", now=now).params == [
        int(datetime(2026, 3, 1, tzinfo=timezone.utc).timestamp())
    ]
    assert parse("before:2025 ", now=now).params == [
        int(datetime(2026, 1, 1, tzinfo=timezone.utc).timestamp())
    ]


def test_relative_dates():
    now = datetime(2026, 5, 10, tzinfo=timezone.utc)
    parsed = parse("newer_than:7d ", now=now)
    assert parsed.params == [int((now - timedelta(days=7)).timestamp())]
    assert parse("after:today ", now=now).params == [
        int(now.replace(hour=0, minute=0, second=0, microsecond=0).timestamp())
    ]


def test_unreadable_date_is_reported_not_silently_dropped():
    parsed = parse("after:sometime ")
    assert parsed.where == []
    assert parsed.warnings and "after:sometime" in parsed.warnings[0]


def test_sort_field():
    assert parse("invoice sort:oldest ").sort == q.SORT_OLDEST
    assert parse("invoice sort:newest ").effective_sort == q.SORT_NEWEST


def test_unknown_field_is_searched_as_text():
    parsed = parse("ticket:1234 ")
    assert parsed.fts == '"ticket:1234"'


def test_empty_query():
    parsed = parse("   ")
    assert parsed.is_empty
    assert parsed.effective_sort == q.SORT_NEWEST


def test_combined_query():
    parsed = parse('from:alice "wire transfer" -draft has:attachment after:2026-01-01 ')
    assert parsed.fts == '(sender:"alice"* AND "wire transfer") NOT ("draft")'
    assert "m.has_attachments = 1" in parsed.where
    assert "m.received_ts >= ?" in parsed.where
