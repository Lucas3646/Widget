# NASDAQ Widget

Widget Android natif pour afficher le NASDAQ 100 directement sur l'écran d'accueil.

## V1

- Widget 4×2 sombre inspiré de la maquette validée
- Valeur, variation en %, variation en points
- Graphique intraday vert
- Heure de dernière mise à jour
- Appui sur le widget = rafraîchissement manuel
- WorkManager = rafraîchissement périodique en arrière-plan
- GitHub Actions = génération automatique d'un APK debug

## État des données

La première version utilise volontairement un flux de démonstration pour valider le rendu et l'installation. Le fournisseur de données live est isolé dans `MarketData.kt` et sera remplacé par une API de marché gratuite après validation du build.

## Installation depuis GitHub

Ouvrir l'onglet **Actions**, sélectionner le dernier workflow **Build Android APK**, puis télécharger l'artifact `nasdaq-widget-debug` et installer `app-debug.apk` sur Android.

Après installation : appui long sur l'écran d'accueil → **Widgets** → **NASDAQ Widget** → ajouter le widget.
