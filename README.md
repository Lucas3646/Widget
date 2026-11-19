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

Le widget de cotation Android utilise Yahoo Finance et affiche l’horodatage du cours. Le drawdown compare ce cours au plus haut des cours historiques hors réinvestissement des dividendes.

### Configuration IBKR sur Android

Dans Client Portal, réglez l’**Activity Flex Query** sur **XML**, date ISO et période **Depuis le début de l’année** pour les dividendes. Dans les sections suivantes, conservez au minimum les champs indiqués.

| Section | Champs nécessaires |
| --- | --- |
| Account Information | Devise de base |
| Net Asset Value (NAV) in Base Currency | Date du relevé, devise et total de la valeur nette |
| Cash Transactions | Type, date/heure, montant, symbole, devise et FX Rate to Base. Utilisez les transactions détaillées. |
| Open Dividend Accruals | Symbole, devise, date de paiement, montant net et FX Rate to Base |
| Change in NAV | TWR, facultatif. Le rendement s’affiche pour la période exacte du relevé d’un seul compte. |

Le montant IBKR correspond à la valeur nette datée du relevé, avec le cash et les droits à dividende comptabilisés par le courtier. Le graphique reprend les relevés IBKR. Les soldes Kraken sont revalorisés aux cours et la variation Kraken représente l’effet des cours sur les soldes courants depuis l’ouverture de la journée UTC. Les deux courtiers ont des dates et des méthodes de calcul distinctes.

Le cumul des dividendes est brut et tient compte des corrections signées. Le montant à recevoir correspond aux droits IBKR encore ouverts, y compris les paiements en attente après leur date prévue. Les montants en EUR s’affichent si la devise est l’euro ou si le relevé donne un taux de conversion vers une devise de base EUR.

## Installation depuis GitHub

Ouvrir l'onglet **Actions**, sélectionner le dernier workflow **Build Android APK**, puis télécharger l'artifact `nasdaq-widget-debug` et installer `app-debug.apk` sur Android.

Après installation : appui long sur l'écran d'accueil → **Widgets** → **NASDAQ Widget** → ajouter le widget.
