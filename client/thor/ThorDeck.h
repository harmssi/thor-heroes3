/*
 * ThorDeck.h, part of the Thor Edition for VCMI (GPL-2.0-or-later)
 *
 * Bridge between the game (top screen) and the touch deck on the AYN Thor's second
 * screen (Java: eu.vcmi.vcmi.thor.ThorBridge). The game pushes a JSON snapshot of the
 * adventure map / battle state; the deck sends back short text commands that run on
 * the GUI thread. Everything is a no-op when no second screen is attached.
 */
#pragma once

namespace ThorDeck
{
/// once per frame on the GUI thread (GameEngine::updateFrame)
void tick();
/// true while the deck is shown -> adventure map uses the full-screen layout
bool active();
/// visible map area in tiles, for the minimap frame on the deck
void onMapViewMoved(int x, int y, int w, int h, int level);
/// battle console text, the deck shows the last lines
void battleLog(const std::string & text);
}
