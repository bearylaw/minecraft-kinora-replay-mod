# Development test arena.
#
#   /function devarena:build
#
# Builds the whole arena from scratch at a fixed place, so running it twice gives the same world
# and a wrecked experiment costs one command to undo. Absolute coordinates on purpose: a bug is
# only worth reporting if someone else can stand where you stood.
#
# Expects a SUPERFLAT world with open sky. The floor replaces the grass at y = -60, so you can walk
# off the edge onto the plain instead of falling to it. Everything is built inside x 0..79,
# z 0..79, from y = -63 up to y = -35. Stations run north to south in sixteen-block rows, each with
# a strip of coloured concrete on the floor in front of it - the colour says which station you are
# in when a screenshot shows only part of the arena.
#
#   z 0..15    plaza, spawn                                         light grey
#   z 16..31   target walls, one material each                      orange
#   z 32..47   open field with rings 3 and 6 blocks out             lime
#   z 48..63   sealed dark room (west) and a pool (east)            blue
#   z 64..79   a roofed pen of mobs that stand still                red
#
# This is a generic arena. Edit it for your mod: add the stations your own bugs need, and keep the
# row-and-colour layout so the table above stays true.

say Building the test arena...

# ---------------------------------------------------------------- clear and floor
#
# In five-block layers, because one fill may touch at most 32768 blocks.

fill 0 -59 0 79 -55 79 air
fill 0 -54 0 79 -50 79 air
fill 0 -49 0 79 -45 79 air
fill 0 -44 0 79 -40 79 air
fill 0 -39 0 79 -35 79 air
fill 0 -63 0 79 -61 79 stone
fill 0 -60 0 79 -60 79 light_gray_concrete

# A low border, so nobody walks off the edge by accident.
fill 0 -59 0 79 -59 0 gray_concrete
fill 0 -59 79 79 -59 79 gray_concrete
fill 0 -59 0 0 -59 79 gray_concrete
fill 79 -59 0 79 -59 79 gray_concrete

# ---------------------------------------------------------------- plaza (z 0..15)

setblock 40 -60 8 gold_block

# ---------------------------------------------------------------- target walls (z 16..31)
#
# Seven walls five high, one material each, all facing the plaza: something to hit that breaks,
# something that does not, something see-through, something that falls.

fill 1 -60 17 78 -60 18 orange_concrete
fill 4 -59 24 11 -55 24 stone
fill 14 -59 24 21 -55 24 oak_planks
fill 24 -59 24 31 -55 24 glass
fill 34 -59 24 41 -55 24 obsidian
fill 44 -59 24 51 -55 24 sand
fill 54 -59 24 61 -55 24 white_wool
fill 64 -59 24 71 -55 24 deepslate_bricks

# ---------------------------------------------------------------- open field (z 32..47)
#
# Rings three and six blocks out from the emerald block, for judging the reach of anything that
# spreads. Drawn as lines: fill's hollow and outline modes treat a one-block-high region as all
# surface and would fill the whole square.

fill 1 -60 33 78 -60 34 lime_concrete
setblock 40 -60 40 emerald_block
fill 37 -60 37 43 -60 37 lime_concrete
fill 37 -60 43 43 -60 43 lime_concrete
fill 37 -60 37 37 -60 43 lime_concrete
fill 43 -60 37 43 -60 43 lime_concrete
fill 34 -60 35 46 -60 35 lime_concrete
fill 34 -60 45 46 -60 45 lime_concrete
fill 34 -60 35 34 -60 45 lime_concrete
fill 46 -60 35 46 -60 45 lime_concrete

# ---------------------------------------------------------------- dark room and pool (z 48..63)
#
# The room is sealed apart from a door, for anything that has to be judged in the dark - light
# sources, glow, emissive rendering. The pool is three deep.

fill 1 -60 48 78 -60 49 blue_concrete
fill 4 -60 50 20 -53 62 deepslate_tiles hollow
setblock 12 -59 50 oak_door[facing=north,half=lower]
setblock 12 -58 50 oak_door[facing=north,half=upper]
fill 44 -62 52 60 -60 60 water

# ---------------------------------------------------------------- mob pen (z 64..79)

fill 1 -60 65 78 -60 66 red_concrete
fill 20 -59 68 60 -59 68 oak_fence
fill 20 -59 78 60 -59 78 oak_fence
fill 20 -59 68 20 -59 78 oak_fence
fill 60 -59 68 60 -59 78 oak_fence
# Roofed, because the arena is pinned to noon and the undead would burn.
fill 20 -55 68 60 -55 78 dark_oak_slab

# ---------------------------------------------------------------- settle the world
#
# Permanent noon and nothing that spawns by itself, so the world does not change underneath an
# experiment. /summon still works.
#
# Snake case, and several renamed outright: 26.2 went through every game rule. doDaylightCycle is
# advance_time, doWeatherCycle is advance_weather, doMobSpawning is spawn_mobs, doInsomnia is
# spawn_phantoms. The old names simply do not parse, and the error points at the rule name without
# saying that it has moved.

gamerule advance_time false
gamerule advance_weather false
gamerule spawn_mobs false
gamerule spawn_monsters false
gamerule spawn_phantoms false
gamerule spawn_patrols false
gamerule spawn_wandering_traders false
gamerule spawn_wardens false
gamerule mob_griefing false
time set noon
weather clear

# Everything that is not a player, then the pen's residents. If the mod adds entities that should
# survive a rebuild, exclude their types here.
kill @e[type=!minecraft:player]
summon zombie 26 -59 73 {NoAI:1b,PersistenceRequired:1b}
summon skeleton 32 -59 73 {NoAI:1b,PersistenceRequired:1b}
summon husk 38 -59 73 {NoAI:1b,PersistenceRequired:1b}
summon pig 44 -59 73 {NoAI:1b,PersistenceRequired:1b}
summon iron_golem 50 -59 73 {NoAI:1b,PersistenceRequired:1b}
summon armor_stand 56 -59 73

setworldspawn 40 -59 8
gamemode creative @s
tp @s 40 -59 8 0 0
give @s templatemod:example_item

say Arena ready. Spawn is the plaza at 40 -59 8, looking south at the target walls.
