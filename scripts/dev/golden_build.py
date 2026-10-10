#!/usr/bin/env python3
"""Plan 58 (alpha.123): builds the decisions golden set v1 from the labelled tables below.

Each request is labelled with the rung it belongs on (``rung``), the rungs that are acceptable (``accept``: a request
that may fairly go one rung higher), the single action when there is one (``capability``, ``target``), its risk flags
(``risks``) and, for chains, its number of steps. The labels follow plan 58 §5: unsure goes up, writing, money,
deleting, accounts, questions and "later" belong to the Mind; one obvious reversible action is Instant; a few routine
steps in one app are Flash.

Grow it with every bug: add a row, run this script, commit both. Output:
``apps/mobile/app/src/main/assets/decisions/golden_v1.jsonl`` (the phone's Lab reads it; the JVM scorer tests it).
"""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "apps/mobile/app/src/main/assets/decisions/golden_v1.jsonl"

RUNGS = ["instant", "flash", "mind", "answer", "ignore"]
rows: list[dict] = []


def add(text, rung, lang="en", accept=None, capability=None, target=None, risks=(), steps=None, tag=""):
    rows.append({"text": text, "lang": lang, "rung": rung, "accept": accept or [rung], "capability": capability,
                 "target": target, "risks": list(risks), "steps": steps, "tag": tag})


# --- Instant: one obvious, reversible action -------------------------------------------------------------------------
for text, cap, lang in [
    ("open camera", "camera", "en"), ("open the camera", "camera", "en"), ("camera", "camera", "en"),
    ("open my camera", "camera", "en"), ("start the camera", "camera", "en"), ("camera please", "camera", "en"),
    ("open de camera", "camera", "nl"), ("camera openen", "camera", "nl"), ("doe de camera open", "camera", "nl"),
    ("take a photo", "photo", "en"), ("take a picture", "photo", "en"), ("snap a photo", "photo", "en"),
    ("maak een foto", "photo", "nl"), ("neem een foto", "photo", "nl"),
    ("take a selfie", "selfie", "en"), ("selfie", "selfie", "en"), ("maak een selfie", "selfie", "nl"),
    ("flashlight on", "flashlight_on", "en"), ("turn on the flashlight", "flashlight_on", "en"), ("torch on", "flashlight_on", "en"),
    ("zaklamp aan", "flashlight_on", "nl"), ("doe de zaklamp aan", "flashlight_on", "nl"),
    ("flashlight off", "flashlight_off", "en"), ("turn off the torch", "flashlight_off", "en"), ("zaklamp uit", "flashlight_off", "nl"),
    ("louder", "volume_up", "en"), ("volume up", "volume_up", "en"), ("turn it up", "volume_up", "en"), ("make it louder", "volume_up", "en"),
    ("harder", "volume_up", "nl"), ("geluid harder", "volume_up", "nl"),
    ("quieter", "volume_down", "en"), ("volume down", "volume_down", "en"), ("turn it down", "volume_down", "en"),
    ("zachter", "volume_down", "nl"), ("geluid zachter", "volume_down", "nl"),
    ("pause", "media_play_pause", "en"), ("pause the music", "media_play_pause", "en"), ("play", "media_play_pause", "en"),
    ("resume the music", "media_play_pause", "en"), ("pauzeer", "media_play_pause", "nl"), ("muziek pauzeren", "media_play_pause", "nl"),
    ("next song", "media_next", "en"), ("skip this song", "media_next", "en"), ("next track", "media_next", "en"),
    ("volgend nummer", "media_next", "nl"),
    ("go back", "back", "en"), ("back", "back", "en"), ("terug", "back", "nl"), ("ga terug", "back", "nl"),
    ("go home", "home", "en"), ("home screen", "home", "en"), ("naar het beginscherm", "home", "nl"),
    ("recent apps", "recents", "en"), ("show my recent apps", "recents", "en"), ("recente apps", "recents", "nl"),
    ("scroll down", "scroll_down", "en"), ("scroll up", "scroll_up", "en"), ("naar beneden scrollen", "scroll_down", "nl"),
    ("scroll naar boven", "scroll_up", "nl"),
    ("swipe up", "swipe_up", "en"), ("swipe left", "swipe_left", "en"), ("swipe right", "swipe_right", "en"),
    ("swipe down", "swipe_down", "en"), ("veeg omhoog", "swipe_up", "nl"), ("veeg naar links", "swipe_left", "nl"),
    ("zoom in", "zoom_in", "en"), ("zoom out", "zoom_out", "en"), ("inzoomen", "zoom_in", "nl"),
]:
    add(text, "instant", lang, capability=cap, tag="action")

for text, app, lang in [
    ("open instagram", "Instagram", "en"), ("open whatsapp", "WhatsApp", "en"), ("open gmail", "Gmail", "en"),
    ("open spotify", "Spotify", "en"), ("open youtube", "YouTube", "en"), ("open maps", "Maps", "en"),
    ("open settings", "Settings", "en"), ("open chrome", "Chrome", "en"), ("open netflix", "Netflix", "en"),
    ("launch spotify", "Spotify", "en"), ("pull up instagram", "Instagram", "en"), ("fire up youtube", "YouTube", "en"),
    ("start whatsapp", "WhatsApp", "en"), ("open the calendar", "Calendar", "en"), ("open photos", "Photos", "en"),
    ("open instagram please", "Instagram", "en"), ("open my gmail", "Gmail", "en"), ("open my calendar", "Calendar", "en"),
    ("open insta", "Instagram", "en"), ("open yt", "YouTube", "en"),
    ("open instagram", "Instagram", "nl"), ("instagram openen", "Instagram", "nl"), ("open whatsapp", "WhatsApp", "nl"),
    ("open de instellingen", "Settings", "nl"), ("open spotify even", "Spotify", "nl"), ("start youtube", "YouTube", "nl"),
    ("open mijn agenda", "Calendar", "nl"), ("open google maps", "Maps", "nl"),
]:
    add(text, "instant", lang, accept=["instant", "flash"] if app in ("Calendar", "Photos") or "insta" == text[-5:] or text.endswith(" yt") else None,
        capability="open_app", target=app, tag="open_app")

for text, lang in [("call mam", "nl"), ("call mom", "en"), ("bel mam", "nl"), ("call dad", "en"), ("bel papa", "nl")]:
    add(text, "instant", lang, accept=["instant", "flash", "mind"], capability=None, tag="call")

for text, lang in [("set a timer for 5 minutes", "en"), ("timer 10 minutes", "en"), ("zet een timer van 3 minuten", "nl"),
                   ("set an alarm for 7", "en"), ("wake me up at 6:30", "en"), ("zet een wekker om 7 uur", "nl")]:
    add(text, "instant", lang, accept=["instant", "flash", "mind"], tag="clock")

# --- Answer: the phone knows -----------------------------------------------------------------------------------------
for text, lang in [("what time is it", "en"), ("what's the time", "en"), ("hoe laat is het", "nl"), ("what day is it", "en"),
                   ("what's the date", "en"), ("welke dag is het", "nl"), ("how much battery do i have", "en"), ("battery", "en"),
                   ("hoeveel batterij heb ik", "nl"), ("what is the date", "en")]:
    add(text, "answer", lang, accept=["answer", "mind"], tag="local")

# --- Ignore: chatter -------------------------------------------------------------------------------------------------
for text, lang in [("ok", "en"), ("thanks", "en"), ("thank you", "en"), ("cool", "en"), ("yeah that's fine", "en"),
                   ("got it", "en"), ("never mind", "en"), ("dank je", "nl"), ("prima", "nl"), ("is goed", "nl"),
                   ("laat maar", "nl"), ("top", "nl"), ("ok thanks", "en"), ("perfect", "en")]:
    add(text, "ignore", lang, accept=["ignore", "flash", "mind"], tag="chatter")

# --- Flash: a few routine steps in one app, nothing written ----------------------------------------------------------
for text, steps, lang in [
    ("turn on dark mode", 2, "en"), ("open wifi settings", 2, "en"), ("open bluetooth settings", 2, "en"),
    ("turn on airplane mode", 2, "en"), ("turn off bluetooth", 2, "en"), ("turn on do not disturb", 2, "en"),
    ("open instagram and go to my dms", 2, "en"), ("open instagram, scroll to my dms and open the first dm", 3, "en"),
    ("open the first email in gmail", 2, "en"), ("open my latest whatsapp chat", 2, "en"),
    ("open youtube and go to my subscriptions", 2, "en"), ("open spotify and go to my library", 2, "en"),
    ("open settings and go to battery", 2, "en"), ("open the battery settings", 2, "en"),
    ("show my instagram profile", 2, "en"), ("go to my instagram notifications", 2, "en"),
    ("open the downloads folder", 2, "en"), ("open my liked songs on spotify", 2, "en"),
    ("open whatsapp and go to calls", 2, "en"), ("open chrome and open a new tab", 2, "en"),
    ("zet de donkere modus aan", 2, "nl"), ("open de wifi instellingen", 2, "nl"), ("zet bluetooth uit", 2, "nl"),
    ("open instagram en ga naar mijn berichten", 2, "nl"), ("open mijn laatste mail", 2, "nl"),
    ("open spotify en ga naar mijn bibliotheek", 2, "nl"), ("ga naar de batterij instellingen", 2, "nl"),
    ("open whatsapp en ga naar de status", 2, "nl"), ("vliegtuigmodus aan", 2, "nl"), ("niet storen aan", 2, "nl"),
    ("open gmail and check which account i'm on", 3, "en"), ("which google account is on this phone", 2, "en"),
    ("open the camera and switch to video", 2, "en"), ("open maps and show my location", 2, "en"),
    ("turn the brightness up", 2, "en"), ("helderheid omhoog", 2, "nl"), ("turn on location", 2, "en"),
    ("open the play store updates", 2, "en"), ("go to my youtube history", 2, "en"), ("open my instagram saved posts", 3, "en"),
]:
    add(text, "flash", lang, accept=["flash", "mind"], steps=steps, tag="steps")

# --- Mind: writing, sending, money, deleting, accounts, questions, later, several apps --------------------------------
for text, risks, lang in [
    ("text mom i'm running late", ["sends_or_posts"], "en"), ("send a whatsapp to lisa saying happy birthday", ["sends_or_posts"], "en"),
    ("reply to the last email", ["sends_or_posts"], "en"), ("tell dad i'll be home at 7", ["sends_or_posts"], "en"),
    ("post this photo on instagram", ["sends_or_posts"], "en"), ("share my location with anna", ["sends_or_posts"], "en"),
    ("message my grandma from my gmail", ["sends_or_posts"], "en"),
    ("open my gmail, check which account is logged in, then message my grandma from it", ["sends_or_posts", "account"], "en"),
    ("comment nice on the first post", ["sends_or_posts"], "en"), ("dm the first person in my instagram requests", ["sends_or_posts"], "en"),
    ("stuur mam dat ik later ben", ["sends_or_posts"], "nl"), ("stuur een appje naar lisa", ["sends_or_posts"], "nl"),
    ("beantwoord de laatste mail", ["sends_or_posts"], "nl"), ("zeg tegen papa dat ik om 7 uur thuis ben", ["sends_or_posts"], "nl"),
    ("deel deze foto op instagram", ["sends_or_posts"], "nl"),
    ("pay the electricity bill", ["money"], "en"), ("buy the cheapest charger on amazon", ["money"], "en"),
    ("order a pizza", ["money"], "en"), ("book a table for two tonight", ["money"], "en"), ("transfer 20 euro to tom", ["money"], "en"),
    ("subscribe to spotify premium", ["money"], "en"), ("betaal de rekening", ["money"], "nl"), ("bestel een pizza", ["money"], "nl"),
    ("koop een nieuwe oplader", ["money"], "nl"), ("maak 20 euro over naar tom", ["money"], "nl"),
    ("delete my last photo", ["destroys"], "en"), ("delete this email", ["destroys"], "en"), ("clear my chrome history", ["destroys"], "en"),
    ("uninstall tiktok", ["destroys"], "en"), ("remove the last alarm", ["destroys"], "en"), ("factory reset the phone", ["destroys"], "en"),
    ("verwijder de laatste foto", ["destroys"], "nl"), ("wis mijn geschiedenis", ["destroys"], "nl"), ("verwijder deze app", ["destroys"], "nl"),
    ("log out of instagram", ["account"], "en"), ("sign in to netflix", ["account"], "en"), ("change my gmail password", ["account"], "en"),
    ("create a new instagram account", ["account"], "en"), ("log in on spotify", ["account"], "en"),
    ("log uit bij instagram", ["account"], "nl"), ("verander mijn wachtwoord", ["account"], "nl"), ("maak een nieuw account aan", ["account"], "nl"),
]:
    add(text, "mind", lang, risks=risks, tag="risky")

for text, lang in [
    ("why is my battery draining so fast", "en"), ("what's the weather tomorrow", "en"), ("how do i turn on developer options", "en"),
    ("who messaged me last", "en"), ("what did anna say in our chat", "en"), ("summarize my unread emails", "en"),
    ("which apps use the most storage", "en"), ("is my phone up to date", "en"), ("how many steps did i walk today", "en"),
    ("waarom gaat mijn batterij zo snel leeg", "nl"), ("wat voor weer wordt het morgen", "nl"), ("wie heeft me net gebeld", "nl"),
    ("vat mijn ongelezen mails samen", "nl"), ("hoeveel opslag heb ik nog", "nl"),
]:
    add(text, "mind", lang, accept=["mind", "flash"], tag="question")

for text, lang in [
    ("remind me to call the dentist tomorrow at 9", "en"), ("turn on the flashlight in 10 minutes", "en"),
    ("when i get home turn on wifi", "en"), ("open spotify at 8 tonight", "en"), ("every morning read me my calendar", "en"),
    ("herinner me morgen om 9 uur aan de tandarts", "nl"), ("zet om 10 uur de wekker uit", "nl"), ("doe straks de zaklamp aan", "nl"),
]:
    add(text, "mind", lang, tag="later")

for text, lang in [
    ("find the cheapest flight to rome next week and show me", "en"), ("compare spotify and youtube music prices", "en"),
    ("copy the address from my last email into maps", "en"), ("save the photo from whatsapp to my gallery and then open it", "en"),
    ("check my calendar and tell me when i'm free tomorrow", "en"), ("look up the restaurant anna sent me and open it in maps", "en"),
    ("set up a new routine that silences my phone at night", "en"), ("go through my photos and find the one with the dog", "en"),
    ("zoek de goedkoopste vlucht naar rome", "nl"), ("kopieer het adres uit mijn mail naar maps", "nl"),
    ("kijk in mijn agenda wanneer ik morgen vrij ben", "nl"), ("zoek de foto met de hond", "nl"),
    ("write a note with my shopping list: milk, eggs, bread", "en"), ("search youtube for how to fix a bike chain", "en"),
    ("schrijf een notitie: melk, eieren, brood", "nl"),
]:
    add(text, "mind", lang, accept=["mind", "flash"] if "search" in text else None, tag="judgement")

# --- Paraphrases and screen-dependent requests: no keyword to lean on -------------------------------------------------
for text, rung, accept, cap, lang in [
    ("pull up the thing i take pictures with", "instant", ["instant", "flash"], "camera", "en"),
    ("i need some light", "instant", ["instant", "flash"], "flashlight_on", "en"),
    ("can't hear anything", "instant", ["instant", "flash", "mind"], "volume_up", "en"),
    ("too loud", "instant", ["instant", "flash"], "volume_down", "en"),
    ("stop the music", "instant", ["instant", "flash"], "media_play_pause", "en"),
    ("i don't like this song", "instant", ["instant", "flash", "mind"], "media_next", "en"),
    ("get me out of this app", "instant", ["instant", "flash"], "home", "en"),
    ("show me more", "instant", ["instant", "flash", "mind"], "scroll_down", "en"),
    ("het is te donker", "instant", ["instant", "flash", "mind"], "flashlight_on", "nl"),
    ("ik hoor niks", "instant", ["instant", "flash", "mind"], "volume_up", "nl"),
    ("weg uit deze app", "instant", ["instant", "flash"], "home", "nl"),
    ("tap that", "flash", ["instant", "flash", "mind"], None, "en"),
    ("tap the blue button", "instant", ["instant", "flash"], "tap", "en"),
    ("press the first one", "instant", ["instant", "flash"], "tap", "en"),
    ("open the second one", "flash", ["instant", "flash", "mind"], None, "en"),
    ("what's this", "mind", ["mind"], None, "en"),
    ("what does this screen say", "mind", ["mind"], None, "en"),
    ("read this to me", "mind", ["mind", "flash"], None, "en"),
    ("wat staat hier", "mind", ["mind"], None, "nl"),
    ("tik op de blauwe knop", "instant", ["instant", "flash"], "tap", "nl"),
    ("open mail", "instant", ["instant", "flash"], "open_app", "en"),
    ("open music", "instant", ["instant", "flash"], "open_app", "en"),
    ("open the bank app", "instant", ["instant", "flash"], "open_app", "en"),
    ("open muziek", "instant", ["instant", "flash"], "open_app", "nl"),
    ("open snapchat", "answer", ["answer", "flash", "mind"], None, "en"),
]:
    target = {"tap the blue button": "Blue button", "tik op de blauwe knop": "Blue button", "press the first one": "First post",
              "open mail": "Gmail", "open music": "Spotify", "open the bank app": "ING", "open muziek": "Spotify"}.get(text)
    add(text, rung, lang, accept=accept, capability=cap, target=target, tag="paraphrase")

# --- Other languages: Triage must route them though the grammar can't parse them --------------------------------------
for text, rung, lang, risks in [
    ("abre la cámara", "instant", "es", []), ("ouvre l'appareil photo", "instant", "fr", []), ("öffne die kamera", "instant", "de", []),
    ("abre instagram", "instant", "es", []), ("ouvre whatsapp", "instant", "fr", []), ("öffne spotify", "instant", "de", []),
    ("sube el volumen", "instant", "es", []), ("plus fort", "instant", "fr", []), ("taschenlampe an", "instant", "de", []),
    ("vai alla home", "instant", "it", []), ("apri la fotocamera", "instant", "it", []), ("abrir o youtube", "instant", "pt", []),
    ("activa el modo oscuro", "flash", "es", []), ("active le mode sombre", "flash", "fr", []), ("dunkelmodus einschalten", "flash", "de", []),
    ("abre instagram y ve a mis mensajes", "flash", "es", []), ("ouvre gmail et regarde quel compte est connecté", "flash", "fr", []),
    ("manda un mensaje a mamá diciendo que llego tarde", "mind", "es", ["sends_or_posts"]),
    ("envoie un message à papa", "mind", "fr", ["sends_or_posts"]), ("schick oma eine nachricht", "mind", "de", ["sends_or_posts"]),
    ("paga la factura de la luz", "mind", "es", ["money"]), ("supprime la dernière photo", "mind", "fr", ["destroys"]),
    ("melde mich bei instagram ab", "mind", "de", ["account"]), ("compra un cargador", "mind", "es", ["money"]),
    ("cancella questa email", "mind", "it", ["destroys"]), ("manda uma mensagem para a mãe", "mind", "pt", ["sends_or_posts"]),
    ("¿por qué se agota mi batería?", "mind", "es", []), ("quel temps fera-t-il demain ?", "mind", "fr", []),
    ("recuérdame mañana a las 9", "mind", "es", []), ("gracias", "ignore", "es", []), ("merci", "ignore", "fr", []), ("danke", "ignore", "de", []),
]:
    accept = {"instant": ["instant", "flash"], "flash": ["flash", "mind"], "ignore": ["ignore", "flash", "mind"]}.get(rung, [rung])
    add(text, rung, lang, accept=accept, risks=risks, tag="other_language")


def main() -> None:
    seen = set()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    for i, row in enumerate(rows, 1):
        key = (row["text"], row["lang"])
        assert key not in seen, key
        seen.add(key)
        assert row["rung"] in RUNGS and all(a in RUNGS for a in row["accept"]) and row["rung"] in row["accept"], row
        out = {"id": f"g{i:03d}", **{k: v for k, v in row.items() if v not in (None, [], "")}}
        lines.append(json.dumps(out, ensure_ascii=False, sort_keys=True))
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    langs = {}
    for row in rows:
        langs[row["lang"]] = langs.get(row["lang"], 0) + 1
    print(f"{len(rows)} requests -> {OUT.relative_to(ROOT)}  {langs}")


if __name__ == "__main__":
    main()
