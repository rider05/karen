package com.karen.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Local TTL cache in front of the providers and the page fetcher. Keys are
 * SHA-256 hashes, so the raw user question is not stored as a key. Expired
 * entries are retained for a short offline window and served only when live
 * retrieval fails.
 */
class SearchCache(
    private val store: SearchCacheStore,
    private val policy: SearchCachePolicy = SearchCachePolicy(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val lastTrimAt = AtomicLong(0L)

    fun search(query: String, variant: String = ""): CachedSearch? = try {
        store.search(SearchCacheKeys.searchKey(query, variant))
    } catch (_: Exception) {
        null
    }

    fun putSearch(query: String, variant: String, hits: List<SearchHit>) {
        val time = now()
        try {
            store.putSearch(
                SearchCacheKeys.searchKey(query, variant),
                CachedSearch(
                    hits = hits.take(MAX_HITS),
                    fetchedAt = time,
                    expiresAt = time + policy.searchTtlMs
                )
            )
        } catch (_: Exception) {
            // Search must still work if the cache is unavailable or corrupt.
        }
    }

    fun page(url: String): CachedPage? = try {
        store.page(SearchCacheKeys.pageKey(url))
    } catch (_: Exception) {
        null
    }

    fun putPage(page: FetchedPage) {
        val time = now()
        try {
            store.putPage(
                SearchCacheKeys.pageKey(page.requestedUrl),
                CachedPage(
                    url = page.finalUrl.ifBlank { page.requestedUrl },
                    title = page.title,
                    text = page.text,
                    canonicalUrl = page.canonicalUrl,
                    publishedAtMillis = page.publishedAtMillis,
                    fetchedAt = time,
                    expiresAt = time + policy.pageTtlMs
                )
            )
        } catch (_: Exception) {
            // Page caching is best-effort; evidence is already in memory.
        }
    }

    fun isFresh(cached: CachedSearch): Boolean = cached.isFresh(now())

    fun isFresh(cached: CachedPage): Boolean = cached.isFresh(now())

    fun canServeStale(cached: CachedSearch): Boolean =
        policy.isRetained(cached.fetchedAt, now())

    fun canServeStale(cached: CachedPage): Boolean =
        policy.isRetained(cached.fetchedAt, now())

    /** Deletes very old rows at most once per hour. Safe to call per request. */
    fun trimIfDue() {
        val time = now()
        val previous = lastTrimAt.get()
        if (previous != 0L && time - previous < TRIM_INTERVAL_MS) return
        if (!lastTrimAt.compareAndSet(previous, time.coerceAtLeast(1L))) return
        try {
            store.trim(time)
        } catch (_: Exception) {
            // A cache trim must never break search.
        }
    }

    companion object {
        private const val MAX_HITS = 10
        private const val TRIM_INTERVAL_MS = 60 * 60_000L
    }
}

/** One DB handle per process; callers create cheap wrappers with their own policy. */
object AndroidSearchCaches {
    @Volatile
    private var store: SearchCacheStore? = null

    fun get(context: Context, policy: SearchCachePolicy = SearchCachePolicy()): SearchCache {
        val current = store ?: synchronized(this) {
            store ?: SqliteSearchCacheStore(context.applicationContext).also { store = it }
        }
        return SearchCache(current, policy)
    }
}

class SqliteSearchCacheStore(
    context: Context,
    name: String = DB_NAME
) : SQLiteOpenHelper(context, name, null, DB_VERSION), SearchCacheStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE search_cache (
                cache_key TEXT PRIMARY KEY NOT NULL,
                payload TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE page_cache (
                cache_key TEXT PRIMARY KEY NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                canonical_url TEXT,
                published_at INTEGER,
                fetched_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_search_fetched ON search_cache(fetched_at)")
        db.execSQL("CREATE INDEX idx_page_fetched ON page_cache(fetched_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS search_cache")
        db.execSQL("DROP TABLE IF EXISTS page_cache")
        onCreate(db)
    }

    @Synchronized
    override fun search(key: String): CachedSearch? {
        readableDatabase.query(
            "search_cache",
            arrayOf("payload", "fetched_at", "expires_at"),
            "cache_key = ?",
            arrayOf(key),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val payload = cursor.getString(0) ?: return null
            return CachedSearch(
                hits = decodeHits(payload),
                fetchedAt = cursor.getLong(1),
                expiresAt = cursor.getLong(2)
            )
        }
    }

    @Synchronized
    override fun putSearch(key: String, value: CachedSearch) {
        val values = ContentValues().apply {
            put("cache_key", key)
            put("payload", encodeHits(value.hits))
            put("fetched_at", value.fetchedAt)
            put("expires_at", value.expiresAt)
        }
        writableDatabase.insertWithOnConflict(
            "search_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    override fun page(key: String): CachedPage? {
        readableDatabase.query(
            "page_cache",
            arrayOf(
                "url", "title", "body", "canonical_url", "published_at",
                "fetched_at", "expires_at"
            ),
            "cache_key = ?",
            arrayOf(key),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return CachedPage(
                url = cursor.getString(0) ?: "",
                title = cursor.getString(1) ?: "",
                text = cursor.getString(2) ?: "",
                canonicalUrl = if (cursor.isNull(3)) null else cursor.getString(3),
                publishedAtMillis = if (cursor.isNull(4)) null else cursor.getLong(4),
                fetchedAt = cursor.getLong(5),
                expiresAt = cursor.getLong(6)
            )
        }
    }

    @Synchronized
    override fun putPage(key: String, value: CachedPage) {
        val values = ContentValues().apply {
            put("cache_key", key)
            put("url", value.url)
            put("title", value.title)
            put("body", value.text)
            put("canonical_url", value.canonicalUrl)
            put("published_at", value.publishedAtMillis)
            put("fetched_at", value.fetchedAt)
            put("expires_at", value.expiresAt)
        }
        writableDatabase.insertWithOnConflict(
            "page_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    override fun trim(now: Long) {
        val cutoff = now - SearchCachePolicy().retainExpiredMs
        writableDatabase.delete("search_cache", "fetched_at < ?", arrayOf(cutoff.toString()))
        writableDatabase.delete("page_cache", "fetched_at < ?", arrayOf(cutoff.toString()))
    }

    private fun encodeHits(hits: List<SearchHit>): String {
        val array = JSONArray()
        hits.forEach { hit ->
            array.put(
                JSONObject().apply {
                    put("t", hit.title)
                    put("u", hit.url)
                    put("s", hit.snippet)
                    put("src", hit.source)
                    put("p", hit.provider)
                    hit.publishedAtMillis?.let { put("d", it) }
                }
            )
        }
        return array.toString()
    }

    private fun decodeHits(payload: String): List<SearchHit> {
        val array = try {
            JSONArray(payload)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<SearchHit>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val title = item.optString("t", "")
            if (title.isBlank()) continue
            out += SearchHit(
                title = title,
                url = item.optString("u", ""),
                snippet = item.optString("s", ""),
                source = item.optString("src", ""),
                publishedAtMillis = if (item.has("d")) item.optLong("d") else null,
                provider = item.optString("p", "")
            )
        }
        return out
    }

    companion object {
        private const val DB_NAME = "karen_search_cache_v1.db"
        private const val DB_VERSION = 1
    }
}
