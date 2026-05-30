from fastapi import FastAPI, Header, Request
from fastapi.responses import JSONResponse, HTMLResponse, FileResponse, StreamingResponse
import asyncio
import hashlib
import html as html_module
import logging
import json
import os
import subprocess
import tempfile
from duckdb import connect
from contextlib import asynccontextmanager
import httpx
from openai import AsyncOpenAI

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)
logging.getLogger("openai").setLevel(logging.WARNING)

# read .env variables
from dotenv import load_dotenv
load_dotenv()
duckdb_file = os.getenv("DUCKDB_FILE", "duckdb_data.db")
openai_api_key = os.getenv("OPENAI_API_KEY", "")
openwebui_base_url = os.getenv("OPENWEBUI_BASE_URL", "")
openwebui_audio_url = os.getenv("OPENWEBUI_AUDIO_URL", "")
openwebui_api_key = os.getenv("OPENWEBUI_API_KEY", "")
groq_api_key = os.getenv("GROQ_API_KEY", "")
transcription_provider = os.getenv("TRANSCRIPTION_PROVIDER", "openai").lower()
transcription_model = os.getenv("TRANSCRIPTION_MODEL", "whisper-1")
transcription_provider_url = os.getenv("TRANSCRIPTION_PROVIDER_URL", "")
transcription_api_key = os.getenv("TRANSCRIPTION_API_KEY", "")
chat_provider = os.getenv("CHAT_PROVIDER", "openai").lower()
chat_model = os.getenv("CHAT_MODEL", "gpt-4o-mini")

GROQ_BASE_URL = "https://api.groq.com/openai/v1"


def _make_client(provider, audio=False):
    if provider == "openwebui":
        return AsyncOpenAI(api_key=openwebui_api_key,
                           base_url=openwebui_audio_url if audio else openwebui_base_url)
    if provider == "groq":
        return AsyncOpenAI(api_key=groq_api_key, base_url=GROQ_BASE_URL)
    if provider == "whisper-api" and audio:
        return AsyncOpenAI(api_key=transcription_api_key or "x",
                           base_url=transcription_provider_url or None)
    return AsyncOpenAI(api_key=openai_api_key)

con = None
PODCASTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "podcasts")
active_tasks = {}


def start_pipeline(url, coro):
    existing = active_tasks.get(url)
    if existing is not None and not existing.done():
        logger.debug(f"Cancelling in-flight pipeline for {url}")
        existing.cancel()
    task = asyncio.create_task(coro)
    active_tasks[url] = task
    task.add_done_callback(lambda t: active_tasks.pop(url, None) if active_tasks.get(url) is t else None)
    return task


def init_db(conn):
    expected = {"url", "status", "ads", "transcript_path", "audio_path", "tokens_in", "tokens_out", "request_count", "created_at", "completed_at", "updated_at", "compute_seconds", "podcast_name", "podcast_url", "episode_title"}
    existing = {row[0] for row in conn.execute(
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'podcasts'"
    ).fetchall()}
    if existing and not expected.issubset(existing):
        conn.execute("DROP TABLE podcasts")
    conn.execute("""
        CREATE TABLE IF NOT EXISTS podcasts (
            url TEXT PRIMARY KEY,
            status TEXT NOT NULL,
            ads TEXT,
            transcript_path TEXT,
            audio_path TEXT,
            podcast_name TEXT,
            podcast_url TEXT,
            episode_title TEXT,
            tokens_in INTEGER,
            tokens_out INTEGER,
            request_count INTEGER NOT NULL DEFAULT 1,
            compute_seconds INTEGER NOT NULL DEFAULT 0,
            created_at TIMESTAMP NOT NULL DEFAULT current_timestamp,
            completed_at TIMESTAMP,
            updated_at TIMESTAMP NOT NULL DEFAULT current_timestamp
        )
    """)


def get_podcast(conn, url):
    row = conn.execute(
        "SELECT status, ads, episode_title, podcast_name FROM podcasts WHERE url = ?", [url]
    ).fetchone()
    if row is None:
        return None
    status, ads_json, episode_title, podcast_name = row
    return {"status": status, "ads": json.loads(ads_json) if ads_json else [],
            "episode_title": episode_title, "podcast_name": podcast_name}


def increment_request_count(conn, url):
    conn.execute(
        "UPDATE podcasts SET request_count = request_count + 1, updated_at = current_timestamp WHERE url = ?",
        [url]
    )


def upsert_podcast(conn, url, status, ads=None, transcript_path=None, audio_path=None, tokens_in=None, tokens_out=None, elapsed_seconds=None, podcast_name=None, podcast_url=None, episode_title=None):
    ads_json = json.dumps(ads) if ads is not None else None
    conn.execute("""
        INSERT INTO podcasts (url, status, ads, transcript_path, audio_path, tokens_in, tokens_out, compute_seconds, podcast_name, podcast_url, episode_title) VALUES (?, ?, ?, ?, ?, ?, ?, COALESCE(?, 0), ?, ?, ?)
        ON CONFLICT (url) DO UPDATE SET
            status = excluded.status,
            ads = excluded.ads,
            transcript_path = COALESCE(excluded.transcript_path, podcasts.transcript_path),
            audio_path = COALESCE(excluded.audio_path, podcasts.audio_path),
            podcast_name = COALESCE(excluded.podcast_name, podcasts.podcast_name),
            podcast_url = COALESCE(excluded.podcast_url, podcasts.podcast_url),
            episode_title = COALESCE(excluded.episode_title, podcasts.episode_title),
            tokens_in = COALESCE(excluded.tokens_in, podcasts.tokens_in),
            tokens_out = COALESCE(excluded.tokens_out, podcasts.tokens_out),
            compute_seconds = podcasts.compute_seconds + excluded.compute_seconds,
            completed_at = CASE WHEN excluded.status = 'complete' THEN now() ELSE podcasts.completed_at END,
            updated_at = now()
    """, [url, status, ads_json, transcript_path, audio_path, tokens_in, tokens_out, elapsed_seconds, podcast_name, podcast_url, episode_title])


CHUNK_SECONDS = int(os.getenv("CHUNK_SECONDS", "600"))
REDO_STEPS = ("download", "transcription", "ad_detection")
GAP_FILL_MS = 30_000
ANTENNAPOD_HEADERS = {
    "User-Agent": "AntennaPod/3.11.3",
    "Accept-Encoding": "identity",
    "Cache-Control": "no-cache",
}


async def download_audio(url, dest_path):
    headers = dict(ANTENNAPOD_HEADERS)
    if url.startswith("http:"):
        headers["Upgrade-Insecure-Requests"] = "1"
    async with httpx.AsyncClient(follow_redirects=True, timeout=300, headers=headers) as client:
        async with client.stream("GET", url) as response:
            response.raise_for_status()
            with open(dest_path, "wb") as f:
                async for chunk in response.aiter_bytes(65536):
                    f.write(chunk)
    return dest_path


async def split_audio(audio_path, dest_dir):
    pattern = os.path.join(dest_dir, "chunk_%04d.mp3")
    proc = await asyncio.create_subprocess_exec(
        "ffmpeg", "-y", "-i", audio_path,
        "-ac", "1", "-b:a", "64k",
        "-f", "segment", "-segment_time", str(CHUNK_SECONDS),
        pattern,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    _, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise subprocess.CalledProcessError(proc.returncode, "ffmpeg", stderr=stderr)
    chunks = sorted(
        [os.path.join(dest_dir, f) for f in os.listdir(dest_dir) if f.startswith("chunk_")]
    )
    return chunks


async def transcribe_chunk(whisper_client, chunk_path, offset_seconds, retries=5):
    for attempt in range(retries):
        try:
            with open(chunk_path, "rb") as f:
                response = await whisper_client.audio.transcriptions.create(
                    model=transcription_model,
                    file=f,
                    response_format="verbose_json",
                    timestamp_granularities=["segment"],
                )
            break
        except Exception as e:
            retry_after = 60
            if hasattr(e, "response") and e.response is not None:
                retry_after = int(e.response.headers.get("retry-after", 60))
            if attempt < retries - 1:
                logger.warning(f"Transcription attempt {attempt + 1} failed: {e}. Retrying in {retry_after}s.")
                await asyncio.sleep(retry_after)
            else:
                raise
    segments = []
    raw_segments = response.segments or []
    if not raw_segments:
        text_preview = (getattr(response, "text", "") or "")[:120]
        logger.warning(f"Transcription returned no segments (response.segments is empty). "
                       f"text preview: {text_preview!r}. "
                       f"Provider may not support verbose_json/timestamp_granularities.")
    for seg in raw_segments:
        segments.append({
            "start": seg.start + offset_seconds,
            "end": seg.end + offset_seconds,
            "text": seg.text.strip(),
        })
    return segments


async def classify_ads(llm_client, segments):
    if not segments:
        return [], 0, 0
    lines = [
        f"[{i}] {int(s['start'] * 1000)}-{int(s['end'] * 1000)}: {s['text']}"
        for i, s in enumerate(segments)
    ]
    prompt = (
        "You are an expert at detecting advertisements and sponsor reads in podcast transcripts.\n"
        "\n"
        "Advertisements often span several consecutive segments — a single ad break typically runs "
        "30–120 seconds. Signals of an ad include:\n"
        "- In the beginning of a podcast episode, they often have ads for /other/ podcasts.\n"
        "- Many ads are played more than once throughout a podcast\n"
        "- Phrases like 'brought to you by', 'sponsored by', 'this episode is supported by', "
        "'today's sponsor', 'a word from our sponsor'\n"
        "- Brand names, product descriptions, pricing, discount codes (e.g. 'use code XYZ')\n"
        "- Website, sale or app mentions (e.g.'blowout sale', 'go to brand.com', 'download the app')\n"
        "- Calls to action: 'sign up', 'try for free', 'check it out', 'click the link', 'get 20% off'\n"
        "- The host directly endorsing or describing a product or service\n"
        "- Topic suddenly shifting away from the main content and then returning\n"
        "- Mentions of other podcasts, especially in a promotional context\n"
        "- References to podcast platforms or ad networks (e.g. 'available on Spotify', 'listen on Apple Podcasts', 'wherever you get your podcasts')\n"
        "- Look out for podcast content resumption phrases (e.g. 'welcome back') to help identify where ads end\n"
        "- Political ads often mention candidates, parties, voting, elections, or political issues\n"
        "\n"
        "IMPORTANT: A single ad break is usually spread across MULTIPLE consecutive segments. "
        "Always use the startMs of the FIRST segment of the ad break and the endMs of the LAST segment "
        "of the same break. It is very unlikely that an ad segment is less than 15 seconds.\n"
        "\n"
        "EXTRA CRITICALLY IMPORTANT: Make a second pass before returning the output. If any ads are close together but separated by a non-ad segment (like one minutes or less of non-ad time between the end of one ad and the start of the next), then it is likely that the time in between the ads is really just more ad content, so mark that as ad content, too.\n"
        "\n"
        "Return a JSON object: {\"ads\": [{\"startMs\": <int>, \"endMs\": <int>}, ...]}\n"
        "If there are no ads return {\"ads\": []}.\n"
        "\n"
        "Transcript segments (format: [index] startMs-endMs: text):\n"
        + "\n".join(lines)
    )
    response = await llm_client.chat.completions.create(
        model=chat_model,
        response_format={"type": "json_object"},
        messages=[{"role": "user", "content": prompt}],
    )
    result = json.loads(response.choices[0].message.content)
    tokens_in = response.usage.prompt_tokens if response.usage else 0
    tokens_out = response.usage.completion_tokens if response.usage else 0
    return result.get("ads", []), tokens_in, tokens_out


def merge_consecutive_ads(ads):
    if not ads:
        return []
    sorted_ads = sorted(ads, key=lambda a: a["startMs"])
    merged = [sorted_ads[0].copy()]
    for ad in sorted_ads[1:]:
        if ad["startMs"] - merged[-1]["endMs"] <= GAP_FILL_MS:
            merged[-1]["endMs"] = max(merged[-1]["endMs"], ad["endMs"])
        else:
            merged.append(ad.copy())
    return merged


async def _run_pipeline(url, audio_path, transcript_path, from_step):
    import shutil
    import time
    row = con.execute("SELECT episode_title FROM podcasts WHERE url = ?", [url]).fetchone()
    episode_title = row[0] if row and row[0] else url
    whisper_client = _make_client(transcription_provider, audio=True)
    llm_client = _make_client(chat_provider, audio=False)
    t_start = time.monotonic()
    try:
        if from_step == "download":
            await download_audio(url, audio_path)
            upsert_podcast(con, url, "pending", audio_path=audio_path)

        if from_step in ("download", "transcription"):
            tmp_dir = tempfile.mkdtemp()
            try:
                chunks = await split_audio(audio_path, tmp_dir)
                logger.info(f"[{episode_title}] Transcribing {len(chunks)} chunks via {transcription_provider}")
                all_segments = []
                for i, chunk_path in enumerate(chunks):
                    logger.info(f"[{episode_title}] Transcription call {i + 1} of {len(chunks)}")
                    segs = await transcribe_chunk(whisper_client, chunk_path, i * CHUNK_SECONDS)
                    all_segments.extend(segs)
            finally:
                await asyncio.to_thread(shutil.rmtree, tmp_dir, True)
            await asyncio.to_thread(lambda: open(transcript_path, "w").write(json.dumps(all_segments)))
            upsert_podcast(con, url, "pending", audio_path=audio_path, transcript_path=transcript_path)
        else:
            all_segments = json.loads(await asyncio.to_thread(lambda: open(transcript_path).read()))

        logger.info(f"[{episode_title}] Ad detection on {len(all_segments)} segments via {chat_provider}")
        raw_ads, tokens_in, tokens_out = await classify_ads(llm_client, all_segments)
        ads = merge_consecutive_ads(raw_ads)
        elapsed = int(time.monotonic() - t_start)
        upsert_podcast(con, url, "complete", ads,
                       transcript_path=transcript_path, audio_path=audio_path,
                       tokens_in=tokens_in, tokens_out=tokens_out, elapsed_seconds=elapsed)
        logger.info(f"[{episode_title}] Pipeline complete (from={from_step}): {len(ads)} ads, tokens in={tokens_in} out={tokens_out}")
    except asyncio.CancelledError:
        logger.debug(f"Pipeline cancelled for {url} (from={from_step})")
        raise
    except Exception as e:
        logger.error(f"Pipeline failed for {url} (from={from_step}): {e}")
        elapsed = int(time.monotonic() - t_start)
        upsert_podcast(con, url, "failed", audio_path=audio_path, elapsed_seconds=elapsed)


async def detect_ads(url):
    logger.debug(f"Starting ad detection for {url}")
    os.makedirs(PODCASTS_DIR, exist_ok=True)
    url_hash = hashlib.md5(url.encode()).hexdigest()
    audio_path = os.path.join(PODCASTS_DIR, f"{url_hash}.mp3")
    transcript_path = os.path.join(PODCASTS_DIR, f"{url_hash}.transcript.json")
    await _run_pipeline(url, audio_path, transcript_path, "download")


def _paths_for(url):
    os.makedirs(PODCASTS_DIR, exist_ok=True)
    url_hash = hashlib.md5(url.encode()).hexdigest()
    audio_path = os.path.join(PODCASTS_DIR, f"{url_hash}.mp3")
    transcript_path = os.path.join(PODCASTS_DIR, f"{url_hash}.transcript.json")
    return audio_path, transcript_path


_inflight_downloads = {}


async def _tee_stream(url, audio_path, transcript_path):
    part_path = audio_path + ".part"
    upsert_podcast(con, url, "pending")
    headers = dict(ANTENNAPOD_HEADERS)
    if url.startswith("http:"):
        headers["Upgrade-Insecure-Requests"] = "1"
    client = httpx.AsyncClient(follow_redirects=True, timeout=300, headers=headers)
    response_cm = client.stream("GET", url)
    response = await response_cm.__aenter__()
    try:
        response.raise_for_status()
        out_headers = {}
        for h in ("content-type", "content-length", "accept-ranges"):
            v = response.headers.get(h)
            if v is not None:
                out_headers[h] = v
        out_headers.setdefault("content-type", "audio/mpeg")

        async def body():
            f = open(part_path, "wb")
            ok = False
            try:
                async for chunk in response.aiter_bytes(65536):
                    f.write(chunk)
                    yield chunk
                ok = True
            finally:
                f.close()
                try:
                    await response_cm.__aexit__(None, None, None)
                finally:
                    await client.aclose()
                if ok and os.path.exists(part_path):
                    try:
                        os.replace(part_path, audio_path)
                        logger.info(f"Cached audio for {url} -> {audio_path}")
                        upsert_podcast(con, url, "pending", audio_path=audio_path)
                        start_pipeline(url, _run_pipeline(url, audio_path, transcript_path, "transcription"))
                    except OSError as e:
                        logger.warning(f"Failed to finalize cache for {url}: {e}")
                else:
                    if os.path.exists(part_path):
                        try:
                            os.remove(part_path)
                        except OSError:
                            pass
                _inflight_downloads.pop(url, None)

        return body(), out_headers
    except BaseException:
        try:
            await response_cm.__aexit__(None, None, None)
        finally:
            await client.aclose()
        _inflight_downloads.pop(url, None)
        raise


async def _proxy_passthrough(url, range_header):
    headers = dict(ANTENNAPOD_HEADERS)
    if url.startswith("http:"):
        headers["Upgrade-Insecure-Requests"] = "1"
    if range_header:
        headers["Range"] = range_header
    client = httpx.AsyncClient(follow_redirects=True, timeout=300, headers=headers)
    response_cm = client.stream("GET", url)
    response = await response_cm.__aenter__()
    try:
        out_headers = {}
        for h in ("content-type", "content-length", "accept-ranges", "content-range"):
            v = response.headers.get(h)
            if v is not None:
                out_headers[h] = v
        out_headers.setdefault("content-type", "audio/mpeg")
        status = response.status_code

        async def body():
            try:
                async for chunk in response.aiter_bytes(65536):
                    yield chunk
            finally:
                try:
                    await response_cm.__aexit__(None, None, None)
                finally:
                    await client.aclose()

        return body(), out_headers, status
    except BaseException:
        try:
            await response_cm.__aexit__(None, None, None)
        finally:
            await client.aclose()
        raise


@asynccontextmanager
async def lifespan(app: FastAPI):
    global con
    con = connect(duckdb_file)
    init_db(con)
    deleted = con.execute("DELETE FROM podcasts WHERE status = 'pending' RETURNING url").fetchall()
    for (url,) in deleted:
        logger.debug(f"Discarded incomplete pending record for {url}")
    yield
    con.close()

app = FastAPI(lifespan=lifespan)

"""
This endpoint provides ad skip information for a given media URL.
It returns a JSON response containing the status and a list of
ad segments with their start and end times in milliseconds.
"""
@app.post("/adskip/")
async def adskip(request: dict):
    podcast_media_url = request.get("url", None)
    if not podcast_media_url:
        logger.warning("No URL provided in the request")
        return JSONResponse(status_code=400, content={"error": "URL is required"})

    podcast_name = request.get("podcast_name")
    podcast_url = request.get("podcast_url")
    episode_title = request.get("episode_title")

    record = get_podcast(con, podcast_media_url)
    if record is not None and record["status"] != "failed":
        logger.debug(f"Cache hit for {podcast_media_url}: status={record['status']}")
        increment_request_count(con, podcast_media_url)
        return JSONResponse(status_code=200, content=record)

    if record is not None and record["status"] == "failed":
        logger.debug(f"Retrying failed detection for {podcast_media_url}")
    else:
        logger.debug(f"New URL, inserting as pending: {podcast_media_url}")
    upsert_podcast(con, podcast_media_url, "pending",
                   podcast_name=podcast_name, podcast_url=podcast_url, episode_title=episode_title)
    start_pipeline(podcast_media_url, detect_ads(podcast_media_url))
    return JSONResponse(status_code=200, content={"status": "pending", "ads": []})


@app.post("/redo/{step}")
async def redo(step: str, request: dict):
    if step not in REDO_STEPS:
        return JSONResponse(status_code=400, content={
            "error": f"Unknown step '{step}'. Valid steps: {', '.join(REDO_STEPS)}"
        })

    url = request.get("url")
    if not url:
        return JSONResponse(status_code=400, content={"error": "URL is required"})

    row = con.execute(
        "SELECT audio_path, transcript_path FROM podcasts WHERE url = ?", [url]
    ).fetchone()
    if row is None:
        return JSONResponse(status_code=404, content={"error": "URL not found"})

    audio_path, transcript_path = row

    if step == "transcription" and not audio_path:
        return JSONResponse(status_code=400, content={
            "error": "No audio file on record; use step 'download' instead"
        })
    if step == "ad_detection" and not transcript_path:
        return JSONResponse(status_code=400, content={
            "error": "No transcript on record; use step 'transcription' or 'download' instead"
        })

    logger.debug(f"Redo from '{step}' for {url}")
    upsert_podcast(con, url, "pending")
    start_pipeline(url, _run_pipeline(url, audio_path, transcript_path, step))
    return JSONResponse(status_code=200, content={"status": "pending", "step": step})


@app.get("/files/{filename}")
async def serve_file(filename: str):
    if "/" in filename or "\\" in filename or filename.startswith("."):
        return JSONResponse(status_code=400, content={"error": "invalid filename"})
    path = os.path.join(PODCASTS_DIR, filename)
    if not os.path.isfile(path):
        return JSONResponse(status_code=404, content={"error": "not found"})
    return FileResponse(path)


@app.get("/", response_class=HTMLResponse)
async def index():
    rows = con.execute(
        "SELECT url, status, ads, audio_path, transcript_path, "
        "podcast_name, podcast_url, episode_title, "
        "tokens_in, tokens_out, request_count, "
        "created_at, completed_at, "
        "CASE WHEN compute_seconds > 0 THEN printf('%ds', compute_seconds) ELSE NULL END AS time_to_complete "
        "FROM podcasts ORDER BY updated_at DESC"
    ).fetchall()
    columns = ["url", "status", "ads", "audio_path", "transcript_path",
               "podcast_name", "podcast_url", "episode_title",
               "tokens_in", "tokens_out", "request_count",
               "created_at", "completed_at", "time_to_complete"]
    header = "".join(f"<th>{c}</th>" for c in columns)
    body_rows = ""
    for row in rows:
        cells = ""
        for col, val in zip(columns, row):
            if val is None:
                cells += "<td></td>"
            elif col in ("audio_path", "transcript_path"):
                fname = os.path.basename(val)
                cells += f'<td><a href="/files/{html_module.escape(fname)}">{html_module.escape(fname)}</a></td>'
            elif col in ("url", "podcast_url"):
                escaped = html_module.escape(str(val))
                cells += f'<td><a href="{escaped}" target="_blank" rel="noopener">{escaped}</a></td>'
            else:
                cells += f"<td>{html_module.escape(str(val))}</td>"
        body_rows += f"<tr>{cells}</tr>"
    html = f"""<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>adskip DB</title>
  <style>
    body {{ font-family: monospace; padding: 1em; }}
    table {{ border-collapse: collapse; width: 100%; }}
    th, td {{ border: 1px solid #ccc; padding: 4px 8px; text-align: left; vertical-align: top; word-break: break-all; }}
    th {{ background: #eee; }}
    tr:nth-child(even) {{ background: #f9f9f9; }}
  </style>
</head>
<body>
  <h1>adskip database ({len(rows)} rows)</h1>
  <table><thead><tr>{header}</tr></thead><tbody>{body_rows}</tbody></table>
</body>
</html>"""
    return HTMLResponse(content=html)


@app.get("/adskip/")
async def adskip_stream(url: str, request: Request, range: str = Header(default=None),
                        t: str = None, pn: str = None):
    if not url or not (url.startswith("http://") or url.startswith("https://")):
        return JSONResponse(status_code=400, content={"error": "missing or invalid 'url'"})
    audio_path, transcript_path = _paths_for(url)

    record = get_podcast(con, url)
    if t or pn:
        if record is None:
            upsert_podcast(con, url, "pending", episode_title=t, podcast_name=pn)
            record = get_podcast(con, url)
        elif (t and not record.get("episode_title")) or (pn and not record.get("podcast_name")):
            upsert_podcast(con, url, record["status"], episode_title=t, podcast_name=pn)
            record = get_podcast(con, url)
    episode_label = (record.get("episode_title") or record.get("podcast_name") if record else None) or url

    if os.path.isfile(audio_path):
        if record is None:
            upsert_podcast(con, url, "pending")
            start_pipeline(url, _run_pipeline(url, audio_path, transcript_path, "transcription"))
            logger.info(f"[{episode_label}] Serving cached audio, pipeline started")
        else:
            increment_request_count(con, url)
            logger.info(f"[{episode_label}] Serving cached audio (status={record['status']})")
        return FileResponse(audio_path, media_type="audio/mpeg")

    if range and range.strip() and range.strip() != "bytes=0-":
        logger.info(f"[{episode_label}] Passthrough (range request): {range}")
        body, headers, status = await _proxy_passthrough(url, range)
        return StreamingResponse(body, status_code=status, headers=headers,
                                 media_type=headers.get("content-type", "audio/mpeg"))

    if url in _inflight_downloads:
        logger.info(f"[{episode_label}] Passthrough (download in-flight)")
        body, headers, status = await _proxy_passthrough(url, range)
        return StreamingResponse(body, status_code=status, headers=headers,
                                 media_type=headers.get("content-type", "audio/mpeg"))

    logger.info(f"[{episode_label}] Starting tee-stream download")
    _inflight_downloads[url] = True
    try:
        body, headers = await _tee_stream(url, audio_path, transcript_path)
    except Exception as e:
        _inflight_downloads.pop(url, None)
        logger.error(f"[{episode_label}] Failed to start tee stream: {e}")
        return JSONResponse(status_code=502, content={"error": str(e)})
    return StreamingResponse(body, headers=headers,
                             media_type=headers.get("content-type", "audio/mpeg"))


@app.get("/timestamps")
async def timestamps(u: str):
    if not u:
        return JSONResponse(status_code=400, content={"error": "missing 'u'"})
    record = get_podcast(con, u)
    if record is None:
        return JSONResponse(content={"status": "pending", "ads": []})
    if record["status"] == "complete":
        return JSONResponse(content={"status": "ready", "ads": record["ads"]})
    return JSONResponse(content={"status": "pending", "ads": []})

