/*
 * ThorDeck.cpp, part of the Thor Edition for VCMI (GPL-2.0-or-later)
 */
#include "StdInc.h"
#include "ThorDeck.h"

#ifndef VCMI_ANDROID

bool ThorDeck::active() { return false; }
void ThorDeck::tick() {}
void ThorDeck::onMapViewMoved(int, int, int, int, int) {}
void ThorDeck::battleLog(const std::string &) {}

#else

#include "../CPlayerInterface.h"
#include "../GameEngine.h"
#include "../GameInstance.h"
#include "../PlayerLocalState.h"
#include "../adventureMap/AdventureMapInterface.h"
#include "../battle/BattleInterface.h"
#include "../battle/BattleStacksController.h"
#include "../battle/BattleWindow.h"
#include "../gui/EventDispatcher.h"
#include "../gui/Shortcut.h"
#include "../gui/ShortcutHandler.h"
#include "../gui/WindowHandler.h"
#include "../render/Graphics.h"
#include "../render/IFont.h"
#include "../render/IImage.h"
#include "../render/IRenderHandler.h"
#include "../adventureMap/AdventureMapShortcuts.h"
#include "../windows/CCastleInterface.h"

#include "../../lib/CAndroidVMHelper.h"
#include "../../lib/CConfigHandler.h"
#include "../../lib/GameLibrary.h"
#include "../../lib/battle/CPlayerBattleCallback.h"
#include "../../lib/entities/faction/CTown.h"
#include "../../lib/entities/faction/CTownHandler.h"
#include "../../lib/spells/CSpellHandler.h"
#include "../../lib/spells/ISpellMechanics.h"
#include "../../lib/spells/Problem.h"
#include "../../lib/spells/adventure/AdventureSpellEffect.h"
#include "../../lib/texts/CGeneralTextHandler.h"
#include "../../lib/CCreatureHandler.h"
#include "../../lib/CStack.h"
#include "../../lib/TerrainHandler.h"
#include "../../lib/callback/CCallback.h"
#include "../../lib/json/JsonNode.h"
#include "../../lib/mapObjects/CGHeroInstance.h"
#include "../../lib/mapObjects/CGTownInstance.h"
#include "../../lib/mapping/CMapHeader.h"
#include "../../lib/mapping/TerrainTile.h"

#include <SDL_surface.h>
#include <jni.h>

namespace
{
constexpr const char * BRIDGE_CLASS = "eu/vcmi/vcmi/thor/ThorBridge";

bool deckShown = false;
int frame = 0;
std::string lastState;
std::string lastMinimap;
int minimapVersion = 0;
std::array<int, 5> view{}; // x, y, w, h, level
std::deque<std::string> logLines;

struct Jni
{
	jclass cls = nullptr;
	jmethodID poll = nullptr;
	jmethodID push = nullptr;
	jmethodID art = nullptr;

	JNIEnv * env()
	{
		CAndroidVMHelper vm;
		JNIEnv * e = vm.get();
		if(!cls)
		{
			jclass local = e->FindClass(BRIDGE_CLASS);
			if(!local)
			{
				e->ExceptionClear();
				return nullptr;
			}
			cls = static_cast<jclass>(e->NewGlobalRef(local));
			e->DeleteLocalRef(local);
			poll = e->GetStaticMethodID(cls, "poll", "()Ljava/lang/String;");
			push = e->GetStaticMethodID(cls, "push", "(Ljava/lang/String;)V");
			art = e->GetStaticMethodID(cls, "art", "(Ljava/lang/String;II[B[I)V");
		}
		return e;
	}

	/// pending deck commands; first line is "A" (deck shown) or "N"
	std::string pollCommands()
	{
		JNIEnv * e = env();
		if(!e || !poll)
			return "N";
		auto js = static_cast<jstring>(e->CallStaticObjectMethod(cls, poll));
		if(e->ExceptionCheck() || !js)
		{
			e->ExceptionClear();
			return "N";
		}
		const char * chars = e->GetStringUTFChars(js, nullptr);
		std::string out(chars ? chars : "N");
		e->ReleaseStringUTFChars(js, chars);
		e->DeleteLocalRef(js); // the SDL thread never returns to Java: free every local ref
		return out;
	}

	void pushState(const std::string & json)
	{
		JNIEnv * e = env();
		if(!e || !push)
			return;
		jstring js = e->NewStringUTF(json.c_str());
		e->CallStaticVoidMethod(cls, push, js);
		if(e->ExceptionCheck())
			e->ExceptionClear();
		e->DeleteLocalRef(js);
	}

	/// pixels: w*h*4 bytes R,G,B,A; meta: glyph widths of a font atlas (else empty)
	void pushArt(const std::string & key, int w, int h, const std::vector<uint8_t> & pixels, const std::vector<jint> & meta)
	{
		JNIEnv * e = env();
		if(!e || !art)
			return;
		jstring js = e->NewStringUTF(key.c_str());
		jbyteArray px = e->NewByteArray(static_cast<jsize>(pixels.size()));
		e->SetByteArrayRegion(px, 0, static_cast<jsize>(pixels.size()), reinterpret_cast<const jbyte *>(pixels.data()));
		jintArray mt = e->NewIntArray(static_cast<jsize>(meta.size()));
		e->SetIntArrayRegion(mt, 0, static_cast<jsize>(meta.size()), meta.data());
		e->CallStaticVoidMethod(cls, art, js, w, h, px, mt);
		if(e->ExceptionCheck())
			e->ExceptionClear();
		e->DeleteLocalRef(js);
		e->DeleteLocalRef(px);
		e->DeleteLocalRef(mt);
	}
} jni;

SDL_Surface * rgbaSurface(int w, int h)
{
	SDL_Surface * s = SDL_CreateRGBSurfaceWithFormat(0, w, h, 32, SDL_PIXELFORMAT_ABGR8888); // bytes in memory: R,G,B,A
	if(s)
		SDL_FillRect(s, nullptr, 0);
	return s;
}

std::vector<uint8_t> pixelsOf(SDL_Surface * s)
{
	std::vector<uint8_t> out(static_cast<size_t>(s->w) * s->h * 4);
	for(int y = 0; y < s->h; ++y)
		memcpy(out.data() + static_cast<size_t>(y) * s->w * 4, static_cast<const uint8_t *>(s->pixels) + y * s->pitch, static_cast<size_t>(s->w) * 4);
	return out;
}

/// Interface art for the deck, read from the player's own game files at runtime (nothing is shipped):
/// "def:<animation>:<frame>[:<player>]", "img:<image>[:<player>]", "font:<big|medium|small|tiny|calli>".
/// A font is sent as one atlas of the characters 32..255 in white, meta = advance of every character.
void sendArt(const std::string & key)
{
	std::vector<std::string> parts;
	boost::split(parts, key, boost::is_any_of(":"));
	SDL_Surface * surface = nullptr;
	std::vector<jint> meta;
	try
	{
		if(parts.size() == 2 && parts[0] == "font")
		{
			static const std::map<std::string, EFonts> fonts = {{"big", FONT_BIG}, {"medium", FONT_MEDIUM}, {"small", FONT_SMALL}, {"tiny", FONT_TINY}, {"calli", FONT_CALLI}};
			const auto font = ENGINE->renderHandler().loadFont(fonts.at(parts[1]));
			const int height = static_cast<int>(font->getLineHeightScaled());
			std::vector<std::string> glyphs;
			int total = 0;
			for(int c = 32; c < 256; ++c)
			{
				std::string ch = c < 128 ? std::string(1, static_cast<char>(c)) : std::string{static_cast<char>(0xC0 | c >> 6), static_cast<char>(0x80 | (c & 63))};
				// advance = what one more of this character adds to a line (a lone glyph also counts its bearings)
				int w = c >= 127 && c < 160 ? 0 : static_cast<int>(font->getStringWidthScaled(ch + ch) - font->getStringWidthScaled(ch));
				glyphs.push_back(ch);
				meta.push_back(w);
				total += w + 4;
			}
			surface = rgbaSurface(total, height);
			int x = 0;
			for(size_t i = 0; surface && i < glyphs.size(); ++i)
			{
				if(meta[i] > 0)
				{
					// one glyph at a time: fonts place text in unscaled coordinates, the atlas is in pixels
					SDL_Surface * g = rgbaSurface(meta[i] + 4, height);
					font->renderTextLeft(g, glyphs[i], ColorRGBA(255, 255, 255), Point(0, 0));
					SDL_SetSurfaceBlendMode(g, SDL_BLENDMODE_NONE);
					SDL_Rect dst{x, 0, g->w, g->h};
					SDL_BlitSurface(g, nullptr, surface, &dst);
					SDL_FreeSurface(g);
				}
				x += meta[i] + 4;
			}
		}
		else if(parts.size() >= 2 && (parts[0] == "def" || parts[0] == "img"))
		{
			const bool def = parts[0] == "def";
			if(def && parts.size() < 3)
				throw std::runtime_error("frame missing");
			std::shared_ptr<IImage> img = def
				? ENGINE->renderHandler().loadImage(AnimationPath::builtin(parts[1]), std::stoi(parts[2]), 0, EImageBlitMode::COLORKEY)
				: ENGINE->renderHandler().loadImage(ImagePath::builtin(parts[1]), EImageBlitMode::OPAQUE);
			const size_t playerPart = def ? 3 : 2;
			if(img && parts.size() > playerPart)
				img->playerColored(PlayerColor(std::stoi(parts[playerPart])));
			if(img && img->width() > 0 && img->height() > 0 && img->width() <= 2048 && img->height() <= 2048)
			{
				surface = rgbaSurface(img->width(), img->height());
				if(surface)
					img->draw(surface, Point(0, 0), nullptr, 1);
			}
		}
	}
	catch(const std::exception & e)
	{
		logGlobal->warn("Thor deck: no art for %s (%s)", key, e.what());
	}
	if(surface)
	{
		jni.pushArt(key, surface->w, surface->h, pixelsOf(surface), meta);
		SDL_FreeSurface(surface);
	}
	else
		jni.pushArt(key, 0, 0, {}, {}); // the deck then draws this piece itself
}

std::string base64(const std::vector<uint8_t> & in)
{
	static const char * t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	std::string out;
	out.reserve((in.size() + 2) / 3 * 4);
	for(size_t i = 0; i < in.size(); i += 3)
	{
		uint32_t v = in[i] << 16 | (i + 1 < in.size() ? in[i + 1] << 8 : 0) | (i + 2 < in.size() ? in[i + 2] : 0);
		out += t[v >> 18 & 63];
		out += t[v >> 12 & 63];
		out += i + 1 < in.size() ? t[v >> 6 & 63] : '=';
		out += i + 2 < in.size() ? t[v & 63] : '=';
	}
	return out;
}

/// same colours as the in-game minimap (CMinimapInstance::getTileColor)
ColorRGBA tileColor(const int3 & pos)
{
	const TerrainTile * tile = GAME->interface()->cb->getTile(pos, false);
	if(!tile)
		return ColorRGBA(0, 0, 0);
	for(const ObjectInstanceID objectID : tile->blockingObjects)
	{
		const auto * obj = GAME->interface()->cb->getObj(objectID, false);
		if(!obj)
			continue;
		PlayerColor player = obj->getOwner();
		if(player == PlayerColor::NEUTRAL)
			return graphics->neutralColor;
		if(player.isValidPlayer())
			return graphics->playerColors[player.getNum()];
	}
	if(tile->blocked() && !tile->visitable())
		return tile->getTerrain()->minimapBlocked;
	return tile->getTerrain()->minimapUnblocked;
}

std::string minimap(int level)
{
	int3 size = GAME->interface()->cb->getMapSize();
	std::vector<uint8_t> rgb;
	rgb.reserve(size.x * size.y * 3);
	for(int y = 0; y < size.y; ++y)
		for(int x = 0; x < size.x; ++x)
		{
			ColorRGBA c = tileColor(int3(x, y, level));
			rgb.push_back(c.r);
			rgb.push_back(c.g);
			rgb.push_back(c.b);
		}
	return base64(rgb);
}

JsonNode armyOf(const CArmedInstance * army)
{
	JsonNode out;
	for(const auto & slot : army->Slots())
	{
		JsonNode s;
		s["slot"].Integer() = slot.first.getNum();
		s["name"].String() = slot.second->getCount() == 1 ? slot.second->getCreature()->getNameSingularTranslated()
														  : slot.second->getCreature()->getNamePluralTranslated();
		s["count"].Integer() = slot.second->getCount();
		s["level"].Integer() = slot.second->getCreature()->getLevel();
		s["icon"].Integer() = slot.second->getCreature()->getIconIndex();
		out.Vector().push_back(s);
	}
	return out;
}

JsonNode heroNode(const CGHeroInstance * h, const CGHeroInstance * selected)
{
	JsonNode n;
	n["id"].Integer() = h->id.getNum();
	n["name"].String() = h->getNameTranslated();
	n["level"].Integer() = h->level;
	n["icon"].Integer() = h->getIconIndex();
	n["mp"].Integer() = h->movementPointsRemaining();
	n["mpMax"].Integer() = h->movementPointsLimit(true);
	n["mana"].Integer() = h->mana;
	n["manaMax"].Integer() = h->manaLimit();
	n["sleeping"].Bool() = GAME->interface()->localState->isHeroSleeping(h);
	n["selected"].Bool() = h == selected;
	n["attack"].Integer() = h->getPrimSkillLevel(PrimarySkill::ATTACK);
	n["defense"].Integer() = h->getPrimSkillLevel(PrimarySkill::DEFENSE);
	n["power"].Integer() = h->getPrimSkillLevel(PrimarySkill::SPELL_POWER);
	n["knowledge"].Integer() = h->getPrimSkillLevel(PrimarySkill::KNOWLEDGE);
	return n;
}

/// spells of one kind the hero can cast, with cost; "ok" = castable right now
JsonNode spellsOf(const CGHeroInstance * h, bool combat, const std::function<bool(const CSpell *)> & castable)
{
	JsonNode out;
	out.setType(JsonNode::JsonType::DATA_VECTOR);
	if(!h || !h->hasSpellbook())
		return out;
	for(const auto & sp : LIBRARY->spellh->objects)
	{
		if(!sp || sp->isCombat() != combat || sp->isCreatureAbility() || !h->canCastThisSpell(sp.get()))
			continue;
		JsonNode n;
		const int cost = GAME->interface()->cb->getSpellCost(sp.get(), h);
		n["id"].Integer() = sp->getId().getNum();
		n["name"].String() = sp->getNameTranslated();
		n["icon"].Integer() = sp->getIconIndex();
		n["cost"].Integer() = cost;
		n["ok"].Bool() = cost <= h->mana && castable(sp.get());
		out.Vector().push_back(n);
	}
	return out;
}

std::string battleSpellsKey;
JsonNode battleSpells;

JsonNode battleNode(const BattleInterface & b)
{
	JsonNode n;
	const CStack * s = b.stacksController ? b.stacksController->getActiveStack() : nullptr;
	if(s)
	{
		JsonNode u;
		u["name"].String() = s->getName();
		u["count"].Integer() = s->getCount();
		u["hp"].Integer() = s->getFirstHPleft();
		u["hpMax"].Integer() = s->getMaxHealth();
		u["attack"].Integer() = s->getAttack(false);
		u["defense"].Integer() = s->getDefense(false);
		u["dmgMin"].Integer() = s->getMinDamage(false);
		u["dmgMax"].Integer() = s->getMaxDamage(false);
		u["speed"].Integer() = s->getMovementRange();
		u["shooter"].Bool() = s->isShooter();
		u["shots"].Integer() = s->isShooter() ? s->shots.available() : 0;
		u["side"].Integer() = static_cast<int>(s->unitSide());
		u["icon"].Integer() = s->creatureIconIndex();
		n["unit"] = u;
	}
	std::vector<battle::Units> order;
	b.getBattle()->battleGetTurnOrder(order, 14, 0);
	for(size_t turn = 0; turn < order.size(); ++turn)
		for(const auto * unit : order[turn])
		{
			JsonNode q;
			q["icon"].Integer() = unit->creatureIconIndex();
			q["count"].Integer() = unit->getCount();
			q["side"].Integer() = static_cast<int>(unit->unitSide());
			q["turn"].Integer() = static_cast<int>(turn);
			n["queue"].Vector().push_back(q);
		}
	n["speed"].Integer() = static_cast<int>(std::round(settings["battle"]["speedFactor"].Float()));
	const CGHeroInstance * own = b.curInt && b.attackingHeroInstance && b.attackingHeroInstance->getOwner() == b.curInt->playerID
		? b.attackingHeroInstance : b.defendingHeroInstance;
	if(own)
	{
		n["hero"].String() = own->getNameTranslated();
		n["mana"].Integer() = own->mana;
		n["manaMax"].Integer() = own->manaLimit();
		n["heroIcon"].Integer() = own->getIconIndex();
		// the spell list only changes with the active stack or the mana: do not test every spell 4x per second
		std::string key = std::to_string(s ? s->unitId() : -1) + ":" + std::to_string(own->mana) + ":" + std::to_string(b.round);
		if(key != battleSpellsKey)
		{
			battleSpellsKey = key;
			const bool canCast = s && !b.tacticsMode && b.getBattle()->battleCanCastSpell(own, spells::Mode::HERO) == ESpellCastProblem::OK;
			battleSpells = spellsOf(own, true, [&](const CSpell * sp)
			{
				spells::detail::ProblemImpl problem;
				return canCast && sp->canBeCast(problem, b.getBattle().get(), spells::Mode::HERO, own);
			});
		}
		n["spells"] = battleSpells;
	}
	n["tactics"].Bool() = b.tacticsMode;
	for(const auto & line : logLines)
		n["log"].Vector().emplace_back(line);
	return n;
}

std::string buildState()
{
	JsonNode st;
	CPlayerInterface * pi = GAME->interface();
	if(!pi || !pi->cb)
	{
		st["mode"].String() = "menu";
		return st.toCompactString();
	}
	if(CPlayerInterface::battleInt)
		st["mode"].String() = "battle";
	else if(ENGINE->windows().topWindow<CCastleInterface>())
		st["mode"].String() = "town";
	else if(adventureInt && ENGINE->windows().topWindow<AdventureMapInterface>())
		st["mode"].String() = "adventure";
	else
		st["mode"].String() = "dialog";

	// a dialog above the battle (retreat? surrender? result): the deck offers accept / cancel
	st["modal"].Bool() = CPlayerInterface::battleInt && !ENGINE->windows().topWindow<BattleWindow>();
	st["player"].Integer() = pi->playerID.getNum();
	st["day"].Integer() = pi->cb->getDate(Date::DAY_OF_WEEK);
	st["week"].Integer() = pi->cb->getDate(Date::WEEK);
	st["month"].Integer() = pi->cb->getDate(Date::MONTH);
	for(int r = 0; r < 7; ++r)
		st["res"].Vector().emplace_back(static_cast<si64>(pi->cb->getResourceAmount(GameResID(r))));

	const CGHeroInstance * hero = pi->localState->getCurrentHero();
	const CGTownInstance * town = pi->localState->getCurrentTown();
	for(const auto * h : pi->localState->getWanderingHeroes())
		st["heroes"].Vector().push_back(heroNode(h, hero));
	for(const auto * t : pi->localState->getOwnedTowns())
	{
		JsonNode n;
		n["id"].Integer() = t->id.getNum();
		n["name"].String() = t->getNameTranslated();
		n["selected"].Bool() = t == town;
		n["fort"].Integer() = static_cast<int>(t->fortLevel());
		n["built"].Bool() = t->built >= 1; // shortcut: per-turn build limit is 1 in H3 rules
		n["icon"].Integer() = t->getTown()->clientInfo.icons[t->hasFort()][t->built >= 1];
		st["towns"].Vector().push_back(n);
	}
	if(const CArmedInstance * army = pi->localState->getCurrentArmy())
	{
		st["army"] = armyOf(army);
		st["armyOf"].String() = hero ? hero->getNameTranslated() : town ? town->getNameTranslated() : "";
	}

	if(hero)
		st["spells"] = spellsOf(hero, false, [&](const CSpell * sp)
		{
			spells::detail::ProblemImpl problem;
			return pi->makingTurn && sp->getAdventureMechanics().canBeCast(problem, pi->cb.get(), hero);
		});
	if(adventureInt)
	{
		// what the game itself allows right now (same checks as its own side panel buttons)
		auto & sc = adventureInt->getAdventureShortcuts();
		JsonNode & can = st["can"];
		can["endTurn"].Bool() = sc.optionCanEndTurn();
		can["hero"].Bool() = sc.optionHeroSelected();
		can["move"].Bool() = sc.optionHeroCanMove();
		can["next"].Bool() = sc.optionHasNextHero();
		can["quests"].Bool() = sc.optionCanViewQuests();
		can["level"].Bool() = sc.optionCanToggleLevel();
		can["market"].Bool() = sc.optionMarketplace();
		can["dig"].Bool() = sc.optionHeroDig();
		can["worldView"].Bool() = sc.optionInWorldView();
		can["quickSave"].Bool() = sc.optionIsLocal();
		can["quickLoad"].Bool() = sc.optionQuickSaveLoad();
		can["visit"].Bool() = sc.optionCanVisitObject();
	}

	int3 size = pi->cb->getMapSize();
	st["map"]["w"].Integer() = size.x;
	st["map"]["h"].Integer() = size.y;
	st["map"]["levels"].Integer() = pi->cb->getMapHeader()->levels();
	st["map"]["view"].Vector() = {JsonNode(view[0]), JsonNode(view[1]), JsonNode(view[2]), JsonNode(view[3]), JsonNode(view[4])};
	st["map"]["version"].Integer() = minimapVersion;

	if(CPlayerInterface::battleInt)
		st["battle"] = battleNode(*CPlayerInterface::battleInt);
	return st.toCompactString();
}

const CGObjectInstance * objectById(const std::string & arg)
{
	if(!GAME->interface() || arg.empty())
		return nullptr;
	return GAME->interface()->cb->getObj(ObjectInstanceID(std::stoi(arg)), false);
}

void runCommand(const std::string & line)
{
	auto sp = line.find(' ');
	std::string cmd = line.substr(0, sp);
	std::string arg = sp == std::string::npos ? "" : line.substr(sp + 1);
	CPlayerInterface * pi = GAME->interface();

	if(cmd == "art")
	{
		sendArt(arg);
		return;
	}
	if(cmd == "speed")
	{
		Settings speed = settings.write["battle"]["speedFactor"];
		speed->Float() = static_cast<float>(std::clamp(std::atoi(arg.c_str()), 1, 3));
		return;
	}
	if(cmd == "bspell")
	{
		// same path as a click in the spell book: check, then the player picks the target on the top screen
		auto b = CPlayerInterface::battleInt;
		const CSpell * sp = SpellID(std::atoi(arg.c_str())).toSpell();
		if(!pi || !b || !sp || !sp->isCombat() || b->tacticsMode || !b->stacksController || !b->stacksController->getActiveStack())
			return;
		const CGHeroInstance * h = b->getBattle()->battleGetMyHero();
		if(!h || !h->canCastThisSpell(sp) || b->getBattle()->battleCanCastSpell(h, spells::Mode::HERO) != ESpellCastProblem::OK)
			return;
		if(pi->cb->getSpellCost(sp, h) > h->mana)
		{
			pi->showInfoDialog(boost::str(boost::format(LIBRARY->generaltexth->allTexts[206]) % pi->cb->getSpellCost(sp, h) % h->mana));
			return;
		}
		spells::detail::ProblemImpl problem;
		if(sp->canBeCast(problem, b->getBattle().get(), spells::Mode::HERO, h))
			b->castThisSpell(sp->getId());
		else
		{
			std::vector<std::string> texts;
			problem.getAll(texts);
			pi->showInfoDialog(texts.empty() ? LIBRARY->generaltexth->translate("vcmi.adventureMap.spellUnknownProblem") : texts.front());
		}
		return;
	}
	if(cmd == "key")
	{
		// several names = one key with several meanings, like Enter (accept + return) on a keyboard
		std::vector<std::string> names;
		boost::split(names, arg, boost::is_any_of(","));
		std::vector<EShortcut> keys;
		for(const auto & name : names)
			if(EShortcut sc = ENGINE->shortcuts().findShortcut(name); sc != EShortcut::NONE)
				keys.push_back(sc);
		if(keys.empty())
			return;
		ENGINE->events().dispatchShortcutPressed(keys);
		ENGINE->events().dispatchShortcutReleased(keys);
		return;
	}
	if(!pi || !adventureInt || CPlayerInterface::battleInt)
		return;
	// deck selection only acts on the plain adventure map, never under a dialog
	if(!ENGINE->windows().topWindow<AdventureMapInterface>())
		return;

	if(cmd == "hero" || cmd == "town")
	{
		const auto * obj = dynamic_cast<const CArmedInstance *>(objectById(arg));
		if(!obj || obj->getOwner() != pi->playerID)
			return;
		if(pi->localState->getCurrentArmy() == obj)
		{
			// second tap on the selected entry opens it
			if(const auto * t = dynamic_cast<const CGTownInstance *>(obj))
				pi->openTownWindow(t);
			else if(const auto * h = dynamic_cast<const CGHeroInstance *>(obj))
				pi->openHeroWindow(h);
			return;
		}
		pi->localState->setSelection(obj);
		adventureInt->centerOnObject(obj);
	}
	else if(cmd == "spell")
	{
		// same path as a click in the spell book (CSpellWindow)
		const CGHeroInstance * h = pi->localState->getCurrentHero();
		const CSpell * sp = SpellID(std::atoi(arg.c_str())).toSpell();
		if(!h || !sp || !sp->isAdventure() || !pi->makingTurn || !h->canCastThisSpell(sp))
			return;
		const int cost = pi->cb->getSpellCost(sp, h);
		if(cost > h->mana)
		{
			pi->showInfoDialog(boost::str(boost::format(LIBRARY->generaltexth->allTexts[206]) % cost % h->mana));
			return;
		}
		spells::detail::ProblemImpl problem;
		if(sp->getAdventureMechanics().canBeCast(problem, pi->cb.get(), h))
		{
			if(sp->getAdventureMechanics().getEffectAs<AdventureSpellRangedEffect>(h) != nullptr)
				adventureInt->enterCastingMode(sp);
			else
				pi->cb->castSpell(h, sp->getId());
		}
		else
		{
			std::vector<std::string> texts;
			problem.getAll(texts);
			pi->showInfoDialog(texts.empty() ? LIBRARY->generaltexth->translate("vcmi.adventureMap.spellUnknownProblem") : texts.front());
		}
	}
	else if(cmd == "army")
	{
		// "army <from> <to> m|s": move / swap / merge a stack of the selected army, or split half of it off
		int from = 0, to = 0;
		char mode = 'm';
		const CArmedInstance * army = pi->localState->getCurrentArmy();
		if(!army || !pi->makingTurn || sscanf(arg.c_str(), "%d %d %c", &from, &to, &mode) != 3 || from == to
			|| from < 0 || to < 0 || from >= GameConstants::ARMY_SIZE || to >= GameConstants::ARMY_SIZE || !army->hasStackAtSlot(SlotID(from)))
			return;
		const bool target = army->hasStackAtSlot(SlotID(to));
		const bool same = target && army->getCreature(SlotID(to)) == army->getCreature(SlotID(from));
		if(mode == 's')
		{
			const int half = army->getStackCount(SlotID(from)) / 2;
			if(half > 0 && (!target || same))
				pi->cb->splitStack(army, army, SlotID(from), SlotID(to), half + (target ? army->getStackCount(SlotID(to)) : 0));
		}
		else if(same)
			pi->cb->mergeStacks(army, army, SlotID(from), SlotID(to));
		else
			pi->cb->swapCreatures(army, army, SlotID(from), SlotID(to));
	}
	else if(cmd == "tile")
	{
		int x = 0, y = 0;
		if(sscanf(arg.c_str(), "%d %d", &x, &y) == 2)
			adventureInt->centerOnTile(int3(x, y, view[4]));
	}
}
}

bool ThorDeck::active()
{
	return deckShown;
}

void ThorDeck::onMapViewMoved(int x, int y, int w, int h, int level)
{
	if(level != view[4])
		lastMinimap.clear();
	view = {x, y, w, h, level};
}

void ThorDeck::battleLog(const std::string & text)
{
	if(text.empty())
		return;
	logLines.push_back(text);
	while(logLines.size() > 8)
		logLines.pop_front();
}

void ThorDeck::tick()
{
	++frame;
	if(frame % 3 != 0)
		return;

	std::string cmds = jni.pollCommands();
	std::istringstream in(cmds);
	std::string line;
	std::getline(in, line);
	deckShown = line == "A";
	if(!deckShown)
		return;
	while(std::getline(in, line))
		if(!line.empty())
			runCommand(line);

	if(!CPlayerInterface::battleInt)
	{
		logLines.clear();
		battleSpellsKey.clear();
	}

	if(frame % 15 == 0) // ~4x per second at 60 fps
	{
		CPlayerInterface * pi = GAME->interface();
		if(pi && pi->cb && frame % 60 == 0)
		{
			std::string mm = minimap(view[4]);
			if(mm != lastMinimap)
			{
				lastMinimap = mm;
				++minimapVersion;
				JsonNode m;
				m["minimap"].String() = mm;
				m["version"].Integer() = minimapVersion;
				int3 size = pi->cb->getMapSize();
				m["w"].Integer() = size.x;
				m["h"].Integer() = size.y;
				jni.pushState(m.toCompactString());
				lastState.clear();
			}
		}
		std::string st = buildState();
		if(st != lastState)
		{
			lastState = st;
			jni.pushState(st);
		}
	}
}

#endif
