# Mieux Choisir — appli Android

Scannez (ou saisissez) le code-barres d'un produit alimentaire : l'appli propose
les **5 alternatives** de la même catégorie, vendues en grande surface en France,
qui sont **moins caloriques, moins sucrées, moins grasses et plus protéinées**.

## Données
- **Open Food Facts** (base publique et collaborative, licence ODbL), sans clé d'API.
- Fiche produit : `GET /api/v2/product/{EAN}.json`
- Équivalents : `GET /api/v2/search?categories_tags=…&countries_tags=en:france`

## Algorithme
1. Lecture du produit et de sa catégorie la plus précise.
2. Récupération de 200 produits populaires vendus en France dans cette catégorie
   (on remonte jusqu'à 3 niveaux de catégorie si les résultats sont insuffisants).
3. Filtre « grande surface » : enseigne connue dans `stores_tags`
   (Leclerc, Carrefour, Auchan, Intermarché, U, Lidl, Aldi, Casino, Monoprix…).
   Si moins de 5 résultats, élargissement signalé à l'écran.
4. Score = (1 − kcal) + (1 − sucres) + (1 − lipides) + protéines, chaque valeur
   normalisée sur l'ensemble comparé. On garde les produits meilleurs que la référence
   sur au moins 2 critères sur 4, sans doublon, et on affiche le top 5 avec les écarts.

## Obtenir l'APK (sans rien installer)
1. Créez un dépôt GitHub (privé possible) et poussez ce dossier.
2. Onglet **Actions** → workflow « Build APK » (lancé automatiquement).
3. Téléchargez l'artefact `MieuxChoisir-apk`, décompressez, installez `app-debug.apk`
   sur le téléphone (autoriser « sources inconnues »).

## Avec Android Studio
Ouvrir le dossier → Run. minSdk 26 (Android 8), Kotlin + Jetpack Compose.
Le scan utilise le scanner Google Play Services (aucune permission caméra requise).

## Fichiers
- `OffApi.kt` : client Open Food Facts
- `Recommender.kt` : filtrage grandes surfaces et scoring
- `MainActivity.kt` : interface Compose
