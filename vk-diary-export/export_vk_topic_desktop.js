// export_vk_topic_desktop.js
// Personal data stripped. For educational / archival tooling purposes only.

const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

// ========= НАСТРОЙКИ =========
// Pass your real topic url via env var VK_TOPIC_URL
// Example: https://vk.com/topic-XXXX_YYYY
const TOPIC_BASE_URL =
  process.env.VK_TOPIC_URL || "https://vk.com/topic-XXXX_YYYY";

const OUT_DIR = path.join(process.cwd(), "out_vk");
const STATE_FILE = path.join(OUT_DIR, "vk_state.json");
const JSON_FILE = path.join(OUT_DIR, "topic.json");

const PAGE_STEP = 20;     // offset шаг
const MAX_OFFSET = 1800;  // поменяй под себя
const HEADLESS = false;   // оставь false чтобы видеть что происходит
const WAIT_LOGIN_MS = 60_000;

function ensureDirs() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function cleanText(s) {
  return (s || "").replace(/\u00A0/g, " ").trim();
}

/**
 * Нормализует "сегодня в 1:10" / "вчера в 23:59" -> "28 дек 2025 в 1:10"
 * Формат сделан под твой Java-парсер.
 */
function normalizeRelativeVkDate(dateText) {
  const s = cleanText(dateText).toLowerCase();
  if (!s) return dateText;

  const m = s.match(/^(сегодня|вчера)\s+в\s+(\d{1,2}):(\d{2})$/);
  if (!m) return dateText;

  const when = m[1];
  const hh = m[2];
  const mm = m[3];

  const months = ["янв","фев","мар","апр","май","июн","июл","авг","сен","окт","ноя","дек"];

  const d = new Date(); // дата запуска экспорта
  if (when === "вчера") d.setDate(d.getDate() - 1);

  const day = d.getDate();
  const mon = months[d.getMonth()];
  const year = d.getFullYear();

  return `${day} ${mon} ${year} в ${hh}:${mm}`;
}

// Парс DOM под desktop-верстку (как у тебя)
async function extractDesktop(page) {
  return await page.evaluate(() => {
    const normalize = (s) => (s || "").replace(/\u00A0/g, " ").trim();

    function isLikelyEmojiImg(img) {
      const src = (img.getAttribute("src") || "").toLowerCase();
      const cls = (img.getAttribute("class") || "").toLowerCase();
      const alt = (img.getAttribute("alt") || "");

      // 1) явный emoji путь
      if (src.includes("/emoji/")) return true;

      // 2) часто у эмодзи есть alt с символом/коротким текстом
      if (alt && alt.length <= 4) return true;

      // 3) маленькие иконки (эмодзи/стикеры) обычно имеют маленький размер
      const w = Number(img.getAttribute("width") || 0);
      const h = Number(img.getAttribute("height") || 0);
      if ((w > 0 && w <= 80) || (h > 0 && h <= 80)) return true;

      // 4) иногда emoji/стикеры имеют классы
      if (cls.includes("emoji") || cls.includes("im_emoji")) return true;

      return false;
    }

    function htmlToTextWithEmojiMarkersAndCollect(root) {
      if (!root) return { text: "", emojis: [], photos: [] };

      const el = root.cloneNode(true);

      const emojis = [];
      const photos = [];

      // заменяем img внутри текста на маркеры [[EMOJI:...]]
      for (const img of Array.from(el.querySelectorAll("img"))) {
        const src = img.getAttribute("src") || "";
        if (!src) {
          img.remove();
          continue;
        }

        if (isLikelyEmojiImg(img)) {
          emojis.push(src);
          img.replaceWith(document.createTextNode(`[[EMOJI:${src}]]`));
        } else {
          // это фото/вложение — убираем из текста, но сохраним отдельно
          photos.push(src);
          img.remove();
        }
      }

      // br -> \n
      for (const br of Array.from(el.querySelectorAll("br"))) {
        br.replaceWith(document.createTextNode("\n"));
      }

      const text = (el.textContent || "").replace(/\u00A0/g, " ").trim();
      return { text, emojis, photos };
    }

    // ---- НОВОЕ: фотки из onclick="showPhoto(... type=album ...)" ----
    function decodeVkEscapes(s) {
      return (s || "")
        .replace(/\\\//g, "/")   // https:\/\/ -> https://
        .replace(/&amp;/g, "&"); // &amp; -> &
    }

    function pickBestAlbumUrl(urls) {
      // Берём самую большую по size=WxH, если параметр есть
      let best = null;
      let bestArea = -1;

      for (const u of urls) {
        const m = u.match(/size=(\d+)x(\d+)/i);
        if (m) {
          const w = Number(m[1]);
          const h = Number(m[2]);
          const area = w * h;
          if (area > bestArea) {
            bestArea = area;
            best = u;
          }
        } else if (!best) {
          best = u;
        }
      }

      return best || (urls.length ? urls[urls.length - 1] : null);
    }

    function extractAlbumPhotosFromOnclick(post) {
      const out = [];

      // ВК-шные миниатюры: <a ... onclick="...showPhoto(...)" ...>
      const anchors = post.querySelectorAll('a[onclick*="showPhoto"]');
      for (const a of anchors) {
        const onclick = a.getAttribute("onclick") || "";
        if (!onclick) continue;

        // Вытаскиваем все url до type=album (они в виде https:\/\/...&amp;type=album)
        const raw = onclick.match(/https?:\\\/\\\/[^"' ]+?type=album/gi) || [];
        if (raw.length === 0) continue;

        const urls = raw.map(decodeVkEscapes);
        const best = pickBestAlbumUrl(urls);
        if (best) out.push(best);
      }

      return Array.from(new Set(out.filter(Boolean)));
    }
    // ---------------------------------------------------------------

    const uniq = (arr) => Array.from(new Set((arr || []).filter(Boolean)));

    const posts = Array.from(document.querySelectorAll("div.bp_post"));
    const res = [];

    for (const post of posts) {
      const postId = (post.getAttribute("id") || "").replace("post-", "");
      const author = normalize(post.querySelector("a.bp_author")?.innerText);
      const dateText = normalize(post.querySelector("a.bp_date")?.innerText);

      const textNode = post.querySelector("div.bp_text");
      const parsed = htmlToTextWithEmojiMarkersAndCollect(textNode);
      if (!parsed.text) continue;

      // соберём картинки из поста (отрезаем аватарки по ava=1)
      const allImgs = Array.from(post.querySelectorAll("img"))
        .map((img) => img.getAttribute("src") || "")
        .filter((src) => src && !src.includes("ava=1"));

      // разделим: emoji и фото (грубая эвристика по /emoji/)
      const emojisFromPost = allImgs.filter((src) => (src || "").toLowerCase().includes("/emoji/"));
      const photosFromPost = allImgs.filter((src) => !((src || "").toLowerCase().includes("/emoji/")));

      // НОВОЕ: фотки-альбомы из onclick showPhoto
      const albumPhotos = extractAlbumPhotosFromOnclick(post);

      res.push({
        postId,
        author,
        dateText,
        text: parsed.text, // текст с [[EMOJI:...]] внутри
        emojis: uniq([...parsed.emojis, ...emojisFromPost]),
        photos: uniq([...parsed.photos, ...photosFromPost, ...albumPhotos]),
      });
    }

    return res;
  });
}

async function main() {
  ensureDirs();

  const context = await chromium.launchPersistentContext("", {
    headless: HEADLESS,
    viewport: { width: 1400, height: 900 },
  });

  // Если уже сохраняли сессию — подхватим
  if (fs.existsSync(STATE_FILE)) {
    try {
      const state = JSON.parse(fs.readFileSync(STATE_FILE, "utf-8"));
      await context.addCookies(state.cookies || []);
    } catch (_) {}
  }

  const page = await context.newPage();

  console.log("Открываю тему:", TOPIC_BASE_URL);
  await page.goto(TOPIC_BASE_URL, { waitUntil: "domcontentloaded" });

  // Ждём посты / логин
  let loggedInOk = false;
  try {
    await page.waitForSelector("div.bp_post", { timeout: 10_000 });
    loggedInOk = true;
  } catch (_) {
    console.log("Если VK попросит — залогинься (QR/вход). Жду 60 секунд...");
    await sleep(WAIT_LOGIN_MS);

    try {
      await page.goto(TOPIC_BASE_URL, { waitUntil: "domcontentloaded" });
      await page.waitForSelector("div.bp_post", { timeout: 30_000 });
      loggedInOk = true;
    } catch (e) {
      console.log("Не вижу div.bp_post после логина. Проверь доступ/страницу.");
      throw e;
    }
  }

  if (!loggedInOk) throw new Error("Не удалось подтвердить доступ к постам.");

  // Сохраняем storageState
  const storageState = await context.storageState();
  fs.writeFileSync(STATE_FILE, JSON.stringify(storageState, null, 2), "utf-8");
  console.log("Сессию сохранил в:", STATE_FILE);

  // Сбор
  const all = [];
  const seen = new Set();

  for (let offset = 0; offset <= MAX_OFFSET; offset += PAGE_STEP) {
    const url = `${TOPIC_BASE_URL}?offset=${offset}`;
    console.log(`Читаю offset=${offset} -> ${url}`);

    await page.goto(url, { waitUntil: "domcontentloaded" });
    await page.waitForSelector("div.bp_post", { timeout: 30_000 });

    // retry если внезапно пусто
    let items = await extractDesktop(page);
    if (!items || items.length === 0) {
      console.log("Постов не нашёл на странице. Подожду и попробую ещё раз...");
      await page.waitForTimeout(1500);
      items = await extractDesktop(page);
      if (!items || items.length === 0) {
        console.log("Всё ещё пусто. Пропускаю offset и иду дальше.");
        continue;
      }
    }

    let addedThisPage = 0;

    for (let idx = 0; idx < items.length; idx++) {
      const it = items[idx];

      const author = cleanText(it.author);
      const dateRaw = cleanText(it.dateText);
      const date = normalizeRelativeVkDate(dateRaw); // нормализуем сегодня/вчера
      const text = cleanText(it.text);
      const postId = cleanText(it.postId);

      if (!text) continue;

      // железный ключ: по postId, а если его нет — fallback по offset+idx
      const key = postId
        ? `id:${postId}`
        : `fallback:${offset}:${idx}|${date}|${text.length}|${text.slice(0, 80)}`;

      if (seen.has(key)) continue;
      seen.add(key);

      all.push({
        offset,
        postId,
        author,
        date, // нормализованная дата
        text, // текст с [[EMOJI:...]] внутри
        emojis: (it.emojis || []).map(cleanText).filter(Boolean),
        photos: (it.photos || []).map(cleanText).filter(Boolean),
      });

      addedThisPage++;
    }

    console.log(`  + добавлено: ${addedThisPage}, всего: ${all.length}`);
    fs.writeFileSync(JSON_FILE, JSON.stringify(all, null, 2), "utf-8");
  }

  fs.writeFileSync(JSON_FILE, JSON.stringify(all, null, 2), "utf-8");
  console.log("ГОТОВО. Собрано записей:", all.length);
  console.log("Файл:", JSON_FILE);

  await context.close();
}

main().catch((e) => {
  console.error("ОШИБКА:", e?.message || e);
  process.exit(1);
});
