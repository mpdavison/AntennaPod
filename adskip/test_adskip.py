import json
import pytest
import asyncio
from unittest.mock import patch, AsyncMock, MagicMock
from duckdb import connect
from fastapi.testclient import TestClient

import adskip as app_module
from adskip import (
    init_db, get_podcast, upsert_podcast, increment_request_count,
    detect_ads, merge_consecutive_ads, classify_ads, app, PODCASTS_DIR, GAP_FILL_MS,
)


@pytest.fixture
def db():
    conn = connect(":memory:")
    init_db(conn)
    yield conn
    conn.close()


@pytest.fixture
def client(db):
    with patch.object(app_module, "con", db):
        yield TestClient(app)


# --- DB helper unit tests ---

def test_get_podcast_not_found(db):
    assert get_podcast(db, "https://example.com/episode.mp3") is None


def test_upsert_pending_then_get(db):
    upsert_podcast(db, "https://example.com/episode.mp3", "pending")
    record = get_podcast(db, "https://example.com/episode.mp3")
    assert record == {"status": "pending", "ads": [], "episode_title": None, "podcast_name": None}


def test_upsert_complete_with_ads(db):
    ads = [{"startMs": 5000, "endMs": 60000}]
    upsert_podcast(db, "https://example.com/episode.mp3", "complete", ads)
    record = get_podcast(db, "https://example.com/episode.mp3")
    assert record == {"status": "complete", "ads": ads, "episode_title": None, "podcast_name": None}


def test_upsert_updates_existing(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "pending")
    ads = [{"startMs": 1000, "endMs": 2000}]
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", ads)
    record = get_podcast(db, "https://example.com/ep.mp3")
    assert record["status"] == "complete"
    assert record["ads"] == ads


def test_init_db_idempotent(db):
    init_db(db)  # calling twice should not raise
    upsert_podcast(db, "https://example.com/ep.mp3", "pending")
    assert get_podcast(db, "https://example.com/ep.mp3") is not None


# --- Endpoint tests ---

def test_adskip_missing_url(client):
    response = client.post("/adskip/", json={})
    assert response.status_code == 400
    assert response.json() == {"error": "URL is required"}


def test_adskip_new_url_returns_pending(client):
    response = client.post("/adskip/", json={"url": "https://example.com/new.mp3"})
    assert response.status_code == 200
    assert response.json() == {"status": "pending", "ads": []}


def test_adskip_new_url_is_persisted(client, db):
    client.post("/adskip/", json={"url": "https://example.com/new.mp3"})
    record = get_podcast(db, "https://example.com/new.mp3")
    assert record is not None
    assert record["status"] == "pending"


def test_adskip_cached_complete_result(client, db):
    ads = [{"startMs": 5000, "endMs": 60000}]
    upsert_podcast(db, "https://example.com/done.mp3", "complete", ads)
    response = client.post("/adskip/", json={"url": "https://example.com/done.mp3"})
    assert response.status_code == 200
    assert response.json()["status"] == "complete"
    assert response.json()["ads"] == ads


# --- merge_consecutive_ads tests ---

def test_merge_empty():
    assert merge_consecutive_ads([]) == []


def test_merge_no_overlap():
    gap = GAP_FILL_MS + 1000
    ads = [{"startMs": 1000, "endMs": 2000}, {"startMs": 2000 + gap, "endMs": 3000 + gap}]
    assert merge_consecutive_ads(ads) == ads


def test_merge_fills_small_gap():
    ads = [{"startMs": 0, "endMs": 30000}, {"startMs": 40000, "endMs": 70000}]
    assert merge_consecutive_ads(ads) == [{"startMs": 0, "endMs": 70000}]


def test_merge_does_not_fill_large_gap():
    gap = GAP_FILL_MS + 1000
    ads = [{"startMs": 0, "endMs": 30000}, {"startMs": 30000 + gap, "endMs": 60000 + gap}]
    assert merge_consecutive_ads(ads) == ads


def test_merge_overlapping():
    ads = [{"startMs": 1000, "endMs": 3000}, {"startMs": 2000, "endMs": 4000}]
    assert merge_consecutive_ads(ads) == [{"startMs": 1000, "endMs": 4000}]


def test_merge_adjacent():
    ads = [{"startMs": 1000, "endMs": 2000}, {"startMs": 2000, "endMs": 3000}]
    assert merge_consecutive_ads(ads) == [{"startMs": 1000, "endMs": 3000}]


def test_merge_unsorted_input():
    gap = GAP_FILL_MS + 2000
    ads = [{"startMs": 1000 + gap, "endMs": 2000 + gap}, {"startMs": 1000, "endMs": 2000}]
    assert merge_consecutive_ads(ads) == [
        {"startMs": 1000, "endMs": 2000},
        {"startMs": 1000 + gap, "endMs": 2000 + gap},
    ]


def test_merge_contained():
    ads = [{"startMs": 1000, "endMs": 10000}, {"startMs": 2000, "endMs": 3000}]
    assert merge_consecutive_ads(ads) == [{"startMs": 1000, "endMs": 10000}]


# --- classify_ads tests ---

@pytest.mark.asyncio
async def test_classify_ads_empty_segments():
    ads, tokens_in, tokens_out = await classify_ads(MagicMock(), [])
    assert ads == []
    assert tokens_in == 0
    assert tokens_out == 0


@pytest.mark.asyncio
async def test_classify_ads_returns_ads():
    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.choices[0].message.content = json.dumps(
        {"ads": [{"startMs": 10000, "endMs": 30000}]}
    )
    mock_response.usage.prompt_tokens = 100
    mock_response.usage.completion_tokens = 20
    mock_client.chat.completions.create = AsyncMock(return_value=mock_response)
    segments = [{"start": 10.0, "end": 30.0, "text": "Visit our sponsor"}]
    ads, tokens_in, tokens_out = await classify_ads(mock_client, segments)
    assert ads == [{"startMs": 10000, "endMs": 30000}]
    assert tokens_in == 100
    assert tokens_out == 20


@pytest.mark.asyncio
async def test_classify_ads_no_ads():
    mock_client = MagicMock()
    mock_response = MagicMock()
    mock_response.choices[0].message.content = json.dumps({"ads": []})
    mock_response.usage.prompt_tokens = 50
    mock_response.usage.completion_tokens = 5
    mock_client.chat.completions.create = AsyncMock(return_value=mock_response)
    segments = [{"start": 0.0, "end": 5.0, "text": "Welcome to the show"}]
    ads, tokens_in, tokens_out = await classify_ads(mock_client, segments)
    assert ads == []


# --- detect_ads background task tests ---

@pytest.mark.asyncio
async def test_detect_ads_stores_complete_on_success(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "pending")
    ads = [{"startMs": 5000, "endMs": 60000}]
    with patch.object(app_module, "con", db), \
         patch("adskip.download_audio", new=AsyncMock(return_value="/tmp/audio.mp3")), \
         patch("adskip.split_audio", return_value=["/tmp/chunk_0000.mp3"]), \
         patch("adskip.transcribe_chunk", new=AsyncMock(return_value=[
             {"start": 5.0, "end": 60.0, "text": "Visit our sponsor"}
         ])), \
         patch("adskip.classify_ads", new=AsyncMock(return_value=(ads, 100, 20))), \
         patch("adskip.AsyncOpenAI"), \
         patch("adskip.os.makedirs"), \
         patch("builtins.open", MagicMock()):
        await detect_ads("https://example.com/ep.mp3")
    record = get_podcast(db, "https://example.com/ep.mp3")
    assert record["status"] == "complete"
    assert record["ads"] == ads
    row = db.execute("SELECT transcript_path, audio_path, tokens_in, tokens_out FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] is not None
    assert row[1] is not None
    assert row[2] == 100
    assert row[3] == 20


@pytest.mark.asyncio
async def test_detect_ads_stores_failed_on_error(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "pending")
    with patch.object(app_module, "con", db), \
         patch("adskip.download_audio", new=AsyncMock(side_effect=Exception("network error"))), \
         patch("adskip.AsyncOpenAI"), \
         patch("adskip.os.makedirs"):
        await detect_ads("https://example.com/ep.mp3")
    record = get_podcast(db, "https://example.com/ep.mp3")
    assert record["status"] == "failed"


@pytest.mark.asyncio
async def test_detect_ads_merges_overlapping_ads(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "pending")
    raw_ads = [{"startMs": 1000, "endMs": 3000}, {"startMs": 2000, "endMs": 4000}]
    with patch.object(app_module, "con", db), \
         patch("adskip.download_audio", new=AsyncMock(return_value="/tmp/audio.mp3")), \
         patch("adskip.split_audio", return_value=["/tmp/chunk_0000.mp3"]), \
         patch("adskip.transcribe_chunk", new=AsyncMock(return_value=[])), \
         patch("adskip.classify_ads", new=AsyncMock(return_value=(raw_ads, 0, 0))), \
         patch("adskip.AsyncOpenAI"), \
         patch("adskip.os.makedirs"), \
         patch("builtins.open", MagicMock()):
        await detect_ads("https://example.com/ep.mp3")
    record = get_podcast(db, "https://example.com/ep.mp3")
    assert record["ads"] == [{"startMs": 1000, "endMs": 4000}]


# --- failed retry endpoint test ---

def test_adskip_retries_failed_status(client, db):
    upsert_podcast(db, "https://example.com/fail.mp3", "failed")
    response = client.post("/adskip/", json={"url": "https://example.com/fail.mp3"})
    assert response.status_code == 200
    assert response.json()["status"] == "pending"
    record = get_podcast(db, "https://example.com/fail.mp3")
    assert record["status"] == "pending"


def test_adskip_new_url_fires_background_task(client):
    with patch("adskip.asyncio.create_task") as mock_create_task:
        client.post("/adskip/", json={"url": "https://example.com/new.mp3"})
        mock_create_task.assert_called_once()


def test_upsert_sets_created_and_updated_timestamps(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "pending")
    row = db.execute("SELECT created_at, updated_at, completed_at, transcript_path, audio_path FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] is not None
    assert row[1] is not None
    assert row[2] is None
    assert row[3] is None
    assert row[4] is None


def test_upsert_stores_transcript_and_audio_path(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", [],
                   transcript_path="/podcasts/abc.transcript.json", audio_path="/podcasts/abc.mp3")
    row = db.execute("SELECT transcript_path, audio_path FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] == "/podcasts/abc.transcript.json"
    assert row[1] == "/podcasts/abc.mp3"


def test_upsert_stores_token_counts(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", [],
                   tokens_in=1234, tokens_out=56)
    row = db.execute("SELECT tokens_in, tokens_out FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] == 1234
    assert row[1] == 56


def test_upsert_preserves_existing_audio_path_on_update(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "pending", audio_path="/tmp/abc.mp3")
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", [])
    row = db.execute("SELECT audio_path FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] == "/tmp/abc.mp3"


def test_upsert_sets_completed_at_on_complete(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "pending")
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", [])
    row = db.execute("SELECT completed_at FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] is not None


def test_upsert_accumulates_compute_seconds(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", [], elapsed_seconds=100)
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", [], elapsed_seconds=45)
    row = db.execute("SELECT compute_seconds FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] == 145


def test_increment_request_count(db):
    upsert_podcast(db, "https://example.com/ep.mp3", "complete", [])
    increment_request_count(db, "https://example.com/ep.mp3")
    increment_request_count(db, "https://example.com/ep.mp3")
    row = db.execute("SELECT request_count FROM podcasts WHERE url = ?",
                     ["https://example.com/ep.mp3"]).fetchone()
    assert row[0] == 3  # 1 from insert + 2 increments


def test_adskip_increments_request_count_on_cache_hit(client, db):
    upsert_podcast(db, "https://example.com/done.mp3", "complete", [])
    client.post("/adskip/", json={"url": "https://example.com/done.mp3"})
    client.post("/adskip/", json={"url": "https://example.com/done.mp3"})
    row = db.execute("SELECT request_count FROM podcasts WHERE url = ?",
                     ["https://example.com/done.mp3"]).fetchone()
    assert row[0] == 3  # 1 from insert + 2 hits


def test_lifespan_deletes_pending_on_startup(db):
    upsert_podcast(db, "https://example.com/pending1.mp3", "pending")
    upsert_podcast(db, "https://example.com/pending2.mp3", "pending")
    upsert_podcast(db, "https://example.com/done.mp3", "complete", [])

    with patch("adskip.connect", return_value=db):
        with TestClient(app):
            assert get_podcast(db, "https://example.com/pending1.mp3") is None
            assert get_podcast(db, "https://example.com/pending2.mp3") is None
            assert get_podcast(db, "https://example.com/done.mp3") is not None
