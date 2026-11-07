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
#include "../gui/EventDispatcher.h"
#include "../gui/Shortcut.h"
#include "../gui/ShortcutHandler.h"
#include "../gui/WindowHandler.h"
#include "../render/Graphics.h"
#include "../windows/CCastleInterface.h"

#include "../../lib/CAndroidVMHelper.h"
#include "../../lib/CCreatureHandler.h"
#include "../../lib/CStack.h"
#include "../../lib/TerrainHandler.h"
#include "../../lib/callback/CCallback.h"
#include "../../lib/json/JsonNode.h"
#include "../../lib/mapObjects/CGHeroInstance.h"
#include "../../lib/mapObjects/CGTownInstance.h"
#include "../../lib/mapping/CMapHeader.h"
#include "../../lib/mapping/TerrainTile.h"

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
} jni;

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
		n["unit"] = u;
	}
	const CGHeroInstance * own = b.curInt && b.attackingHeroInstance && b.attackingHeroInstance->getOwner() == b.curInt->playerID
		? b.attackingHeroInstance : b.defendingHeroInstance;
	if(own)
	{
		n["hero"].String() = own->getNameTranslated();
		n["mana"].Integer() = own->mana;
		n["manaMax"].Integer() = own->manaLimit();
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
		st["towns"].Vector().push_back(n);
	}
	if(const CArmedInstance * army = pi->localState->getCurrentArmy())
	{
		st["army"] = armyOf(army);
		st["armyOf"].String() = hero ? hero->getNameTranslated() : town ? town->getNameTranslated() : "";
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

	if(cmd == "key")
	{
		EShortcut sc = ENGINE->shortcuts().findShortcut(arg);
		if(sc == EShortcut::NONE)
			return;
		ENGINE->events().dispatchShortcutPressed({sc});
		ENGINE->events().dispatchShortcutReleased({sc});
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
		logLines.clear();

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
