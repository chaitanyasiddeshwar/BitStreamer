package main

import (
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// subtitleExtToMime maps sidecar subtitle extensions to the exact MIME strings
// ExoPlayer/Media3 expects in a MediaItem.SubtitleConfiguration.
var subtitleExtToMime = map[string]string{
	".srt":  "application/x-subrip",
	".vtt":  "text/vtt",
	".ass":  "text/x-ssa",
	".ssa":  "text/x-ssa",
	".ttml": "application/ttml+xml",
	".dfxp": "application/ttml+xml",
	".sup":  "application/pgs",
}

func subtitleMime(name string) (string, bool) {
	m, ok := subtitleExtToMime[strings.ToLower(filepath.Ext(name))]
	return m, ok
}

// subtitleTrack is one on-disk sidecar subtitle offered to the client.
type subtitleTrack struct {
	Label string `json:"label"` // display name, e.g. "English" / "en (forced)"
	Lang  string `json:"lang"`  // best-effort language code, "" if unknown
	Mime  string `json:"mime"`  // ExoPlayer MIME (see subtitleExtToMime)
	URL   string `json:"url"`   // relative fetch path, e.g. /subtitle?name=...
}

// findSidecarSubtitles scans mediaFull's directory for subtitle files that share
// the movie's base name (movie1.mkv -> movie1.srt, movie1.en.srt, movie1.forced.srt).
// urlFor builds each track's fetch URL from the sidecar's file name.
func findSidecarSubtitles(mediaFull string, urlFor func(name string) string) []subtitleTrack {
	dir := filepath.Dir(mediaFull)
	base := strings.TrimSuffix(filepath.Base(mediaFull), filepath.Ext(mediaFull))
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var subs []subtitleTrack
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		mime, ok := subtitleMime(name)
		if !ok {
			continue
		}
		stem := strings.TrimSuffix(name, filepath.Ext(name)) // e.g. movie1.en
		if !strings.EqualFold(stem, base) && !strings.HasPrefix(strings.ToLower(stem), strings.ToLower(base)+".") {
			continue
		}
		tag := strings.TrimPrefix(stem[len(base):], ".") // "" or "en" or "en.forced"
		label, lang := subtitleLabelAndLang(tag)
		subs = append(subs, subtitleTrack{Label: label, Lang: lang, Mime: mime, URL: urlFor(name)})
	}
	sort.Slice(subs, func(i, j int) bool { return strings.ToLower(subs[i].Label) < strings.ToLower(subs[j].Label) })
	return subs
}

// subtitleLabelAndLang turns the filename tag between the base name and the
// extension (e.g. "en", "en.forced", "English.sdh") into a display label and a
// best-effort language code.
func subtitleLabelAndLang(tag string) (label, lang string) {
	if tag == "" {
		return "External", ""
	}
	parts := strings.FieldsFunc(tag, func(r rune) bool { return r == '.' || r == '-' || r == '_' })
	var flags []string
	langPart := ""
	for _, p := range parts {
		switch strings.ToLower(p) {
		case "forced":
			flags = append(flags, "forced")
		case "sdh", "cc", "hi":
			flags = append(flags, "SDH")
		default:
			if langPart == "" {
				langPart = p
			}
		}
	}
	name := langName(langPart)
	if name == "" {
		name = langPart
	}
	if name == "" {
		name = tag
	}
	if len(flags) > 0 {
		name += " (" + strings.Join(flags, ", ") + ")"
	}
	return name, langCode(langPart)
}

// langNames maps common 2- and 3-letter codes (and a few full names) to a
// display name. Best-effort: unknown tags fall back to the raw text.
var langNames = map[string]string{
	"en": "English", "eng": "English", "english": "English",
	"es": "Spanish", "spa": "Spanish", "spanish": "Spanish",
	"fr": "French", "fra": "French", "fre": "French", "french": "French",
	"de": "German", "ger": "German", "deu": "German", "german": "German",
	"it": "Italian", "ita": "Italian", "italian": "Italian",
	"pt": "Portuguese", "por": "Portuguese", "portuguese": "Portuguese",
	"ru": "Russian", "rus": "Russian", "russian": "Russian",
	"ja": "Japanese", "jpn": "Japanese", "japanese": "Japanese",
	"zh": "Chinese", "chi": "Chinese", "zho": "Chinese", "chinese": "Chinese",
	"ko": "Korean", "kor": "Korean", "korean": "Korean",
	"hi": "Hindi", "hin": "Hindi", "hindi": "Hindi",
	"ar": "Arabic", "ara": "Arabic", "arabic": "Arabic",
	"nl": "Dutch", "dut": "Dutch", "nld": "Dutch", "dutch": "Dutch",
	"sv": "Swedish", "swe": "Swedish", "swedish": "Swedish",
	"no": "Norwegian", "nor": "Norwegian", "norwegian": "Norwegian",
	"da": "Danish", "dan": "Danish", "danish": "Danish",
	"fi": "Finnish", "fin": "Finnish", "finnish": "Finnish",
	"pl": "Polish", "pol": "Polish", "polish": "Polish",
	"tr": "Turkish", "tur": "Turkish", "turkish": "Turkish",
}

func langName(tag string) string { return langNames[strings.ToLower(tag)] }

// langCode returns a language code for a tag if it looks like one (2-3 letters),
// else "".
func langCode(tag string) string {
	t := strings.ToLower(tag)
	if _, ok := langNames[t]; ok || (len(t) >= 2 && len(t) <= 3 && isAlpha(t)) {
		return t
	}
	return ""
}

func isAlpha(s string) bool {
	for _, r := range s {
		if r < 'a' || r > 'z' {
			return false
		}
	}
	return true
}

func subsOrEmpty(s []subtitleTrack) []subtitleTrack {
	if s == nil {
		return []subtitleTrack{}
	}
	return s
}

// handleSubtitle serves a sidecar subtitle file. Single-file mode: ?name= is a
// bare filename resolved inside the movie's directory. Folder mode: ?path= is a
// path relative to the served root (confined by resolvePath). Only files with a
// known subtitle extension are served.
func (a *app) handleSubtitle(w http.ResponseWriter, r *http.Request) {
	var full string
	if a.folderMode {
		p, ok := a.resolvePath(r.URL.Query().Get("path"))
		if !ok {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		full = p
	} else {
		name := r.URL.Query().Get("name")
		if name == "" || name != filepath.Base(name) { // no path separators
			http.Error(w, "bad name", http.StatusBadRequest)
			return
		}
		full = filepath.Join(filepath.Dir(a.mediaPath), name)
	}
	mime, ok := subtitleMime(full)
	if !ok {
		http.Error(w, "not a subtitle", http.StatusNotFound)
		return
	}
	f, err := os.Open(full)
	if err != nil {
		http.Error(w, "subtitle not found", http.StatusNotFound)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil || fi.IsDir() {
		http.Error(w, "not a file", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", mime)
	http.ServeContent(w, r, "", fi.ModTime(), f)
}

// folderSubtitlesFor returns the sidecar subtitles for a file at relative path
// rel (folder mode), with URLs pointing back through /subtitle?path=.
func folderSubtitlesFor(full, rel string) []subtitleTrack {
	relClean := strings.Trim(strings.ReplaceAll(rel, "\\", "/"), "/")
	reldir := ""
	if i := strings.LastIndex(relClean, "/"); i >= 0 {
		reldir = relClean[:i]
	}
	return findSidecarSubtitles(full, func(name string) string {
		rp := name
		if reldir != "" {
			rp = reldir + "/" + name
		}
		return "/subtitle?path=" + url.QueryEscape(rp)
	})
}
