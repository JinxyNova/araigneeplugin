# Araignée d'horreur (plugin Paper)

Basé sur le dépôt https://github.com/TheCymaera/minecraft-spider (licence : usage libre, attribution appréciée).
Tout le code d'origine est conservé ; l'ajout est dans `src/main/kotlin/com/heledron/spideranimation/horror/`.

## Ce qu'elle fait
0. **Apparition automatique** fréquente, à 1 à 15 chunks de toi (config `auto-spawn`). Elle peut disparaître en pleine course, une autre revient ensuite.
1. **Approche** : rapide de très loin, puis lente, avec des ralentissements et des arrêts aléatoires, et des sifflements d'ambiance (elle s'arrête à ~5 blocs et te fixe).
2. Dès que **le joueur la voit** (dans son champ de vision, sans bloc entre eux), elle **se fige 1 à 10 secondes** (durée aléatoire).
3. Si tu la fixes trop longtemps (3 s) ou trop souvent (plus de 3 regards récents), elle ne patiente plus et **fonce 1 à 3 s après** (`behavior.aggro`).
3. Sinon, après son attente, elle **court sur lui** (allure « gallop » du dépôt), attaque au contact, recule et recommence.
4. **Taille aléatoire** : toutes les 4 à 14 s elle grandit ou rétrécit (0.5x à 3x) en plusieurs paliers. Elle grossit aussi quand elle se met à charger.
5. **Couleur changeante** : fondu lent entre des teintes sombres au calme, rouge/os quand elle est figée, rouge vif pendant la charge. Les yeux rouges et le sablier de l'abdomen clignotent.

## Installation
Même environnement que le dépôt d'origine : **Paper 26.2, Java 25** (voir `build.gradle.kts`, je ne l'ai pas modifié).

1. Ouvre le dossier dans IntelliJ IDEA (il importe Gradle tout seul), puis tâche Gradle `build`
   (ou, avec Gradle installé : `gradle build`).
2. Le jar est dans `build/libs/` (celui qui finit par `-all.jar`). Mets-le dans `plugins/` du serveur Paper.
3. Lance le serveur : `plugins/horror-spider/config.yml` est créé.

## Commandes (permission `horror-spider.command`, op par défaut)
- `/horror spawn [taille]` : l'invoque là où tu regardes
- `/horror remove` / `/horror clear` / `/horror list`
- `/horror scale <taille>` : change la taille de la plus proche
- `/horror color <#rrggbb>` ou `/horror color auto`
- `/horror charge` : force la charge (pour tester)
- `/horror reload` : recharge `config.yml`

Les commandes d'origine `/spider ...` marchent toujours.

## Réglages (`config.yml`)
Vitesses, temps d'attente (1 à 10 s), angle de vision, dégâts (`attack.damage: 0` = simple jumpscare),
tailles min/max, palettes de couleurs en hexadécimal, apparition automatique la nuit (désactivée par défaut).

## Où est quoi dans le code
- **Déplacements / états** : `horror/HorrorBehavior.kt` (STALK -> FREEZE -> CHARGE). Il pilote `Locomotion` du dépôt (`walk` / `face`) et bascule `options.gallop`.
- **Couleurs** : `HorrorBehavior.tickColor()` + `spider/components/Cloak.kt` (j'y ai ajouté `tint`, une teinte permanente avec fondu).
- **Taille** : `HorrorBehavior.tickScale()` (même méthode que la commande `/spider scale` du dépôt).
- **Corps** : `horror/HorrorSpiderFactory.kt` (8 pattes `octopod`, corps en block displays, 8 yeux).

## Limites
- Pas de pathfinding : elle va en ligne droite et franchit le relief avec ses pattes, mais un mur haut la bloque.
- Les araignées ne sont pas sauvegardées au redémarrage (comme dans le dépôt d'origine).
- Le dépôt d'origine se dit « très expérimental » en multijoueur.
