package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.core.MarketView
import net.prok.proknet.core.Msisdn
import net.prok.proknet.node.MarketSync

/**
 * v0.19.0: Prok Market on the phone.
 *
 * What this screen is honest about: publication is paid and reviewed, so a seller sees
 * "En attente de confirmation" (the treasurer has not yet seen the money) and then "En
 * attente de modération" (a person has not yet read the listing) - never "publié" before
 * the Brain says so. Buyers browse and write free; the sale itself happens between the two
 * people, in person, and the safety guide on every listing says so. A boosted row always
 * carries the word "Sponsorisé".
 *
 * Plain views only (LinearLayouts, TextViews, Buttons in a ScrollView), like
 * [TreasuryActivity]. Every network call runs on one IO thread; the chat and the payment
 * screen poll every 20 s while they are visible.
 */
class MarketActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val node by lazy { ProkNetApp.node(this) }
    private val market by lazy { MarketSync(node.identity) { node.brainUrlProvider?.invoke() ?: "" } }
    private val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

    private val panes = listOf(R.id.mkBrowse, R.id.mkDetail, R.id.mkChat, R.id.mkSell, R.id.mkPay, R.id.mkMine, R.id.mkPackages)
    private var current: MarketView.Listing? = null
    private var chatOther = ""
    private var chatListing = ""
    private var chatLastId = 0L
    private var payInvoice: MarketView.Invoice? = null
    private var seller: MarketView.SellerStatus? = null
    private var searchOffset = 0
    private var searchTotal = 0
    private val pendingPhotos = ArrayList<ByteArray>()
    private var rail = "MTN"
    private var poller: Runnable? = null

    private val categoryKeys = listOf("") + MarketView.CATEGORIES.keys
    private val conditionKeys = MarketView.CONDITIONS.keys.toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_market)
        Prok.header(this, "Prok Market", "Brazzaville · entre personnes, en main propre")
        findViewById<Button>(R.id.btnMkBrowse).setOnClickListener { show(R.id.mkBrowse); search(reset = true) }
        findViewById<Button>(R.id.btnMkSell).setOnClickListener { openSell() }
        findViewById<Button>(R.id.btnMkMine).setOnClickListener { openMine() }
        findViewById<Button>(R.id.btnMkSearch).setOnClickListener { search(reset = true) }
        findViewById<Button>(R.id.btnMkMore).setOnClickListener { search(reset = false) }
        findViewById<Button>(R.id.btnMkBack).setOnClickListener { show(R.id.mkBrowse) }
        findViewById<Button>(R.id.btnMkContact).setOnClickListener { current?.let { openChat(it.id, it.sellerId, it.title) } }
        findViewById<Button>(R.id.btnMkOffer).setOnClickListener { current?.let { offerDialog(it.id, it.sellerId, it.title) } }
        findViewById<Button>(R.id.btnMkReport).setOnClickListener { current?.let { reportDialog(it) } }
        findViewById<Button>(R.id.btnMkBlock).setOnClickListener { current?.let { blockDialog(it.sellerId) } }
        findViewById<Button>(R.id.btnMkChatBack).setOnClickListener { stopPolling(); if (current != null) show(R.id.mkDetail) else openMine() }
        findViewById<Button>(R.id.btnMkSend).setOnClickListener { sendChat(0) }
        findViewById<Button>(R.id.btnMkChatOffer).setOnClickListener { offerDialog(chatListing, chatOther, text(R.id.mkChatTitle)) }
        findViewById<Button>(R.id.btnMkAddPhoto).setOnClickListener { pickPhoto() }
        findViewById<Button>(R.id.btnMkSubmit).setOnClickListener { submit() }
        findViewById<Button>(R.id.btnMkCopyRef).setOnClickListener { payInvoice?.let { copy(it.reference) } }
        findViewById<Button>(R.id.btnMkPayDone).setOnClickListener { stopPolling(); openMine() }
        findViewById<Button>(R.id.btnMkPackages).setOnClickListener { openPackages() }
        findViewById<Button>(R.id.btnMkInbox).setOnClickListener { inboxDialog() }
        findViewById<Button>(R.id.btnMkPackagesBack).setOnClickListener { openMine() }
        findViewById<Button>(R.id.btnMkBuyPackage5).setOnClickListener { buyPackage("PACKAGE5") }
        findViewById<Button>(R.id.btnMkBuyStorefront).setOnClickListener { buyPackage("STOREFRONT") }
        findViewById<TextView>(R.id.mkSafety).text = MarketView.safetyGuideText()
        spinner(R.id.mkCategory, listOf("Toutes catégories") + MarketView.CATEGORIES.values)
        spinner(R.id.mkSellCategory, MarketView.CATEGORIES.values.toList())
        spinner(R.id.mkSellCondition, MarketView.CONDITIONS.values.toList())
        findViewById<TextView>(R.id.mkPackage5Line).text = MarketView.offerLine("PACKAGE5")
        findViewById<TextView>(R.id.mkStorefrontLine).text = MarketView.offerLine("STOREFRONT")
        findViewById<Button>(R.id.btnMkSellMore).setOnClickListener {
            val more = findViewById<View>(R.id.mkSellMore); val open = more.visibility != View.VISIBLE
            more.visibility = if (open) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.btnMkSellMore).text = if (open) "Moins de détails" else "Ajouter des détails"
        }
        show(R.id.mkBrowse)
        // v0.19.3: opened from the radar - straight to selling, or straight to one listing
        val listingExtra = intent.getStringExtra("listing")
        when {
            intent.getStringExtra("mode") == "sell" -> openSell()
            listingExtra != null -> openDetail(listingExtra)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!market.configured) status("Réseau Prok non configuré : le marché a besoin du Brain.")
        else if (visible(R.id.mkBrowse) && findViewById<LinearLayout>(R.id.mkResults).childCount == 0) search(reset = true)
    }

    override fun onPause() { super.onPause(); stopPolling() }
    override fun onDestroy() { super.onDestroy(); stopPolling(); io.shutdownNow() }

    // ---- browse -----------------------------------------------------------------------------------

    private fun search(reset: Boolean) {
        if (!market.configured) return
        if (reset) { searchOffset = 0; findViewById<LinearLayout>(R.id.mkResults).removeAllViews() }
        val q = text(R.id.mkQuery)
        val cat = categoryKeys[findViewById<PickerView>(R.id.mkCategory).selected.coerceIn(0, categoryKeys.size - 1)]
        val hood = text(R.id.mkNeighbourhood)
        val min = digits(R.id.mkMinPrice) * 100
        val max = digits(R.id.mkMaxPrice) * 100
        val offset = searchOffset
        status("Recherche…")
        io.execute {
            val page = market.search(q, cat, min, max, hood, limit = 20, offset = offset)
            runOnUiThread {
                if (page == null) { status("Brain injoignable : " + market.lastError); return@runOnUiThread }
                status("")
                searchTotal = page.total
                val rows = findViewById<LinearLayout>(R.id.mkResults)
                for (l in page.items) rows.addView(resultRow(l))
                searchOffset = offset + page.items.size
                findViewById<TextView>(R.id.mkResultsInfo).text = if (page.total == 0) "Aucune annonce ne correspond." else page.total.toString() + " annonce" + (if (page.total > 1) "s" else "") + " · les résultats sponsorisés sont marqués « " + MarketView.SPONSORED_LABEL + " »"
                findViewById<Button>(R.id.btnMkMore).visibility = if (searchOffset < page.total) View.VISIBLE else View.GONE
            }
        }
    }

    private fun resultRow(l: MarketView.Listing): View {
        val box = Prok.card(this) { openDetail(l.id) }
        box.addView(Prok.titleRow(this, l.title, if (l.boosted) MarketView.SPONSORED_LABEL else null, Prok.Tone.WARN))
        box.addView(Prok.stat(this, l.priceText, 2))
        box.addView(Prok.muted(this, listOfNotNull(MarketView.categoryName(l.category), l.condition.takeIf { it.isNotEmpty() }?.let { MarketView.conditionName(it) },
            l.neighbourhood.takeIf { it.isNotEmpty() }, l.distanceKm.takeIf { it.isNotEmpty() }?.let { it + " km" }).joinToString(" · "), 2))
        return box
    }

    // ---- detail -----------------------------------------------------------------------------------

    private fun openDetail(id: String) {
        status("Chargement…")
        io.execute {
            val l = market.listing(id)
            runOnUiThread {
                if (l == null) { status("Annonce indisponible : " + market.lastError); return@runOnUiThread }
                status("")
                current = l
                renderDetail(l)
                show(R.id.mkDetail)
                loadPhotos(l)
            }
        }
    }

    private fun renderDetail(l: MarketView.Listing) {
        findViewById<TextView>(R.id.mkDetailSponsored).visibility = if (l.boosted) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.mkDetailTitle).text = l.title
        findViewById<TextView>(R.id.mkDetailPrice).text = l.priceText
        findViewById<TextView>(R.id.mkDetailMeta).text = MarketView.categoryName(l.category) +
            (if (l.condition.isNotEmpty()) " · " + MarketView.conditionName(l.condition) else "") +
            "\n" + l.city + (if (l.neighbourhood.isNotEmpty()) " · " + l.neighbourhood else "") +
            (if (l.pickupOptions.isNotEmpty()) "\nRemise : " + l.pickupOptions else "") +
            (if (l.publishedAt > 0) "\nPubliée le " + dateFmt.format(Date(l.publishedAt)) else "") +
            (if (l.state != "PUBLISHED") "\nStatut : " + l.stateText else "")
        findViewById<TextView>(R.id.mkDetailDesc).text = l.description
        findViewById<TextView>(R.id.mkDetailSeller).text = "Vendeur prok-" + l.sellerId.take(8) + " · " + l.sellerPublished + " annonce" + (if (l.sellerPublished != 1) "s" else "") + " publiée" + (if (l.sellerPublished != 1) "s" else "") +
            (if (l.sellerSince > 0) " · membre depuis le " + dateFmt.format(Date(l.sellerSince)) else "") +
            (if (l.sellerVerified) " · numéro vérifié" else "") + (if (l.sellerStorefront) " · vitrine professionnelle" else "")
        val mine = l.sellerId == market.myId
        for (id in listOf(R.id.btnMkContact, R.id.btnMkOffer, R.id.btnMkReport, R.id.btnMkBlock)) findViewById<Button>(id).visibility = if (mine) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.mkDetailPhotos).removeAllViews()
    }

    private fun loadPhotos(l: MarketView.Listing) {
        val box = findViewById<LinearLayout>(R.id.mkDetailPhotos)
        for (url in l.photoUrls) io.execute {
            val bytes = market.photo(url) ?: return@execute
            val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null } ?: return@execute
            runOnUiThread {
                if (current?.id != l.id) return@runOnUiThread
                box.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                    clipToOutline = true
                    setBackgroundResource(R.drawable.bg_card)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Prok.dp(context, 240)).apply { bottomMargin = Prok.dp(context, 10) }
                })
            }
        }
    }

    private fun reportDialog(l: MarketView.Listing) {
        val kinds = listOf("SAFETY" to "Danger ou arnaque en cours (masquée immédiatement)", "SCAM" to "Escroquerie", "PROHIBITED" to "Contenu interdit", "SPAM" to "Doublon / spam", "OTHER" to "Autre")
        AlertDialog.Builder(this).setTitle("Signaler cette annonce").setItems(kinds.map { it.second }.toTypedArray()) { _, i ->
            ask("Précisez (facultatif)", "Ce que vous avez vu") { t ->
                run { market.report(l.id, kinds[i].first, t) }
            }
        }.setNegativeButton("Annuler", null).show()
    }

    private fun blockDialog(user: String) {
        confirm("Bloquer cette personne", "Vous ne recevrez plus ses messages et elle ne pourra plus vous écrire. Réversible depuis un fil de discussion.") {
            run { market.block(user, true) }
        }
    }

    // ---- chat -------------------------------------------------------------------------------------

    private fun openChat(listingId: String, other: String, title: String) {
        chatListing = listingId; chatOther = other; chatLastId = 0
        findViewById<TextView>(R.id.mkChatTitle).text = title + " · prok-" + other.take(8)
        findViewById<LinearLayout>(R.id.mkChatRows).removeAllViews()
        show(R.id.mkChat)
        startPolling { pollChat() }
    }

    private fun pollChat() {
        val listing = chatListing; val other = chatOther
        io.execute {
            val t = market.thread(listing, other, since = 0)
            runOnUiThread {
                if (t == null || !visible(R.id.mkChat) || chatListing != listing) return@runOnUiThread
                val rows = findViewById<LinearLayout>(R.id.mkChatRows)
                rows.removeAllViews()
                if (t.messages.isEmpty()) rows.addView(Prok.empty(this, "Aucun message pour l'instant. Présentez-vous et proposez un lieu public."))
                for (m in t.messages) rows.addView(bubble(m))
                chatLastId = t.messages.lastOrNull()?.id ?: 0
                findViewById<Button>(R.id.btnMkSend).isEnabled = !t.blocked
                findViewById<TextView>(R.id.mkChatNote).text = if (t.blocked) "Conversation bloquée." else "Messages relayés par Prok, relevés toutes les 20 s. Ne payez rien avant d'avoir l'article en main."
            }
        }
    }

    /** One message as a bubble: mine on the right in brand, theirs on the left on a card. */
    private fun bubble(m: MarketView.Message): View {
        val mine = m.fromId == market.myId
        val c = this
        val body = TextView(c).apply {
            textSize = 15f
            text = (if (m.offerCentimes > 0) "Offre " + MarketView.fcfa(m.offerCentimes) + " - " else "") + m.body
            setTextColor(c.getColor(if (mine) R.color.on_brand else R.color.text))
            setBackgroundResource(if (mine) R.drawable.bg_bubble_me else R.drawable.bg_bubble_other)
            setPadding(Prok.dp(c, 14), Prok.dp(c, 10), Prok.dp(c, 14), Prok.dp(c, 10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        val stamp = Prok.small(c, SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date(m.at)), 2).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Prok.dp(c, 2) }
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) android.view.Gravity.END else android.view.Gravity.START
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Prok.dp(c, 8) }
            addView(body); addView(stamp)
        }
    }

    private fun sendChat(offerCentimes: Long, body: String = text(R.id.mkChatInput)) {
        if (body.isEmpty()) { toast("Message vide"); return }
        if (body.length > MarketView.MAX_MESSAGE) { toast("500 caractères max"); return }
        findViewById<EditText>(R.id.mkChatInput).setText("")
        io.execute {
            val out = market.sendMessage(chatListing, chatOther, body, offerCentimes)
            runOnUiThread { if (!out.ok) toast(out.message); pollChat() }
        }
    }

    private fun offerDialog(listingId: String, to: String, title: String) {
        val input = EditText(this).apply { hint = "Montant proposé (F)"; inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Faire une offre").setMessage("Votre offre est un message au vendeur. Rien n'est payé ici : vous réglez le vendeur en personne, une fois l'article vérifié.").setView(form(input))
            .setPositiveButton("Envoyer") { _, _ ->
                val cfa = input.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                if (cfa <= 0) { toast("Montant requis"); return@setPositiveButton }
                if (!visible(R.id.mkChat) || chatListing != listingId) openChat(listingId, to, title)
                sendChat(cfa * 100, "Je propose " + MarketView.fcfa(cfa * 100) + " pour cet article.")
            }.setNegativeButton("Annuler", null).show()
    }

    private fun inboxDialog() {
        io.execute {
            val rows = market.inbox() ?: emptyList()
            runOnUiThread {
                if (rows.isEmpty()) { toast("Aucune conversation"); return@runOnUiThread }
                val labels = rows.map { (if (it.unread > 0) "(" + it.unread + ") " else "") + it.title + " · prok-" + it.other.take(8) + "\n" + it.lastBody }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Messages").setItems(labels) { _, i -> current = null; openChat(rows[i].listingId, rows[i].other, rows[i].title) }
                    .setNegativeButton("Fermer", null).show()
            }
        }
    }

    // ---- sell ----------------------------------------------------------------------------------------

    private fun openSell() {
        show(R.id.mkSell)
        findViewById<TextView>(R.id.mkSellOffer).text = MarketView.offerLine("POST")
        findViewById<Button>(R.id.btnMkSubmit).text = "Publier - " + MarketView.fcfa(MarketView.PRICES.getValue("POST").centimes)
        io.execute {
            val s = market.me()
            runOnUiThread {
                seller = s
                if (s == null) return@runOnUiThread
                val v = findViewById<TextView>(R.id.mkSellVoucher)
                when {
                    s.voucherAvailable -> { v.visibility = View.VISIBLE; v.text = MarketView.offerLine("VOUCHER") + " - votre première annonce ne vous coûte rien (modération inchangée)."; findViewById<Button>(R.id.btnMkSubmit).text = "Publier - offerte" }
                    s.slotsFree > 0 -> { v.visibility = View.VISIBLE; v.text = "Forfait : " + s.slotsFree + " publication" + (if (s.slotsFree > 1) "s" else "") + " prépayée" + (if (s.slotsFree > 1) "s" else "") + " - décomptée à la publication."; findViewById<Button>(R.id.btnMkSubmit).text = "Publier - 1 publication du forfait" }
                    else -> v.visibility = View.GONE
                }
            }
        }
    }

    private fun pickPhoto() {
        if (pendingPhotos.size >= MarketView.MAX_PHOTOS) { toast("5 photos maximum"); return }
        val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(Intent.createChooser(i, "Choisir une photo"), REQ_PHOTO)
    }

    @Deprecated("plain Activity API, by design")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        io.execute {
            val jpeg = try { contentResolver.openInputStream(uri)?.use { toJpegUnder(it.readBytes(), MarketView.MAX_PHOTO_BYTES) } } catch (e: Exception) { null }
            runOnUiThread {
                if (jpeg == null) { toast("Photo illisible"); return@runOnUiThread }
                pendingPhotos.add(jpeg)
                findViewById<LinearLayout>(R.id.mkSellPhotoStrip).addView(ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                    scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; setBackgroundResource(R.drawable.bg_card)
                    layoutParams = LinearLayout.LayoutParams(Prok.dp(context, 72), Prok.dp(context, 72)).apply { marginEnd = Prok.dp(context, 8) }
                })
                findViewById<Button>(R.id.btnMkAddPhoto).text = if (pendingPhotos.size >= MarketView.MAX_PHOTOS) "5 photos, c'est le maximum" else "Ajouter une autre photo (" + pendingPhotos.size + "/5)"
            }
        }
    }

    /** Any gallery image becomes a JPEG under [limit] bytes: sampled down, then compressed harder. */
    private fun toJpegUnder(raw: ByteArray, limit: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= limit) return bytes
            if (quality > 40) quality -= 15
            else bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 2 / 3, bmp.height * 2 / 3, true)
            if (bmp.width < 200) return null
        }
    }

    /** v0.19.3: what you sell, a price, a photo. The rest is folded; the number and the 18+ line are asked once. */
    private fun submit() {
        val title = text(R.id.mkSellTitle)
        val desc = text(R.id.mkSellDesc).ifEmpty { title }
        val cat = MarketView.CATEGORIES.keys.toList()[findViewById<PickerView>(R.id.mkSellCategory).selected.coerceAtLeast(0)]
        val cond = conditionKeys[findViewById<PickerView>(R.id.mkSellCondition).selected.coerceAtLeast(0)]
        val price = digits(R.id.mkSellPrice) * 100
        val hood = text(R.id.mkSellNeighbourhood)
        val pickup = text(R.id.mkSellPickup)
        if (title.isEmpty()) { toast("Dites ce que vous vendez"); return }
        if (price <= 0) { toast("Indiquez un prix"); return }
        val needPhone = seller?.hasPhone != true
        val needAttest = seller?.adultAttested != true
        if (needPhone || needAttest) firstTimeDialog(needPhone, needAttest) { phone -> publish(title, desc, cat, cond, price, hood, pickup, phone) }
        else publish(title, desc, cat, cond, price, hood, pickup, "")
    }

    private fun firstTimeDialog(needPhone: Boolean, needAttest: Boolean, then: (String) -> Unit) {
        val phone = EditText(this).apply { hint = "Votre numéro Mobile Money (9 chiffres)"; inputType = InputType.TYPE_CLASS_PHONE }
        val attest = CheckBox(this).apply { text = "J'ai 18 ans ou plus et cet article m'appartient." }
        val fields = ArrayList<View>(); if (needPhone) fields += phone; if (needAttest) fields += attest
        AlertDialog.Builder(this).setTitle("Une fois seulement")
            .setMessage(if (needPhone) "Votre numéro sert à payer les services Prok. Il n'est jamais publié." else "")
            .setView(form(*fields.toTypedArray()))
            .setPositiveButton("Continuer") { _, _ ->
                val digits = Msisdn.digits(phone.text.toString())
                if (needPhone && digits.isEmpty()) { toast("Numéro invalide : 9 chiffres attendus"); return@setPositiveButton }
                if (needAttest && !attest.isChecked) { toast("Cochez la ligne 18 ans ou plus"); return@setPositiveButton }
                then(if (needPhone) digits else "")
            }.setNegativeButton("Annuler", null).show()
    }

    private fun publish(title: String, desc: String, cat: String, cond: String, price: Long, hood: String, pickup: String, phone: String) {
        chooseRail { r ->
            status("Envoi de l'annonce…")
            io.execute {
                val reg = market.registerSeller(phone, true)
                if (!reg.ok) { runOnUiThread { status(""); toast(reg.message) }; return@execute }
                val out = market.submitListing(title, desc, cat, price, cond, hood, pickup, 0.0, 0.0, "AUTO", r)
                if (!out.ok) { runOnUiThread { status(""); toast(out.message) }; return@execute }
                val listing = MarketView.listingOf(out.text)
                val invoice = MarketView.invoiceOf(out.text)
                var photoErrors = 0
                if (listing != null) for (p in pendingPhotos) if (!market.uploadPhoto(listing.id, p).ok) photoErrors++
                runOnUiThread {
                    status("")
                    pendingPhotos.clear()
                    findViewById<LinearLayout>(R.id.mkSellPhotoStrip).removeAllViews()
                    findViewById<Button>(R.id.btnMkAddPhoto).text = "Ajouter des photos"
                    for (id in listOf(R.id.mkSellTitle, R.id.mkSellDesc, R.id.mkSellPrice, R.id.mkSellPickup)) findViewById<EditText>(id).setText("")
                    if (photoErrors > 0) toast(photoErrors.toString() + " photo(s) non envoyée(s)")
                    if (invoice != null) openPay(invoice)
                    else { toast(listing?.stateText ?: "Annonce enregistrée"); openMine() }
                }
            }
        }
    }

    // ---- pay ---------------------------------------------------------------------------------------

    private fun openPay(inv: MarketView.Invoice) {
        payInvoice = inv
        renderPay(inv)
        show(R.id.mkPay)
        startPolling { pollInvoice() }
    }

    private fun renderPay(inv: MarketView.Invoice) {
        findViewById<TextView>(R.id.mkPayInstruction).text = inv.instruction.ifEmpty { MarketView.payInstruction(inv.amountCentimes, inv.reference, inv.rail) }
        findViewById<TextView>(R.id.mkPayReference).text = inv.reference
        val now = System.currentTimeMillis()
        findViewById<TextView>(R.id.mkPayState).text = when (inv.state) {
            "OPEN" -> inv.text + " · expire dans " + inv.hoursLeft(now) + " h"
            "PAID" -> inv.text + " · " + (if (inv.service == "POST") MarketView.listingText("AWAITING_REVIEW") else "service activé")
            else -> inv.text
        }
        findViewById<Button>(R.id.btnMkCopyRef).visibility = if (inv.open) View.VISIBLE else View.GONE
    }

    private fun pollInvoice() {
        val id = payInvoice?.id ?: return
        io.execute {
            val inv = market.invoice(id) ?: return@execute
            val listing = if (inv.paid && inv.listingId.isNotEmpty()) market.listing(inv.listingId) else null
            runOnUiThread {
                if (!visible(R.id.mkPay) || payInvoice?.id != id) return@runOnUiThread
                payInvoice = inv
                renderPay(inv)
                if (listing != null) findViewById<TextView>(R.id.mkPayState).text = inv.text + " · " + listing.stateText
                if (!inv.open) stopPolling()
            }
        }
    }

    // ---- mine --------------------------------------------------------------------------------------

    private fun openMine() {
        show(R.id.mkMine)
        status("Chargement…")
        io.execute {
            val s = market.me()
            val listings = market.myListings()
            val invoices = market.myInvoices()
            runOnUiThread {
                status(if (listings == null) "Brain injoignable : " + market.lastError else "")
                seller = s
                findViewById<TextView>(R.id.mkMineStatus).text = if (s == null) "" else
                    (if (s.verified) "Numéro vérifié" else "Numéro non vérifié (vérifié au premier paiement reçu)") +
                    (if (s.voucherAvailable) " · première annonce offerte disponible" else "") +
                    (if (s.slotsFree > 0) " · " + s.slotsFree + " publication(s) prépayée(s)" else "") +
                    (if (s.storefront) " · vitrine active" else "") + " · " + s.publishedCount + " publiée(s)"
                val rows = findViewById<LinearLayout>(R.id.mkMineRows)
                rows.removeAllViews()
                val ls = listings ?: emptyList()
                findViewById<TextView>(R.id.mkMineEmpty).visibility = if (ls.isEmpty()) View.VISIBLE else View.GONE
                val now = System.currentTimeMillis()
                for (l in ls) rows.addView(mineRow(l, now))
                val irows = findViewById<LinearLayout>(R.id.mkInvoiceRows)
                irows.removeAllViews()
                val invs = (invoices ?: emptyList()).take(10)
                if (invs.isEmpty()) irows.addView(Prok.empty(this, "Aucune facture."))
                for (inv in invs) irows.addView(Prok.card(this, onClick = if (inv.open) ({ openPay(inv) }) else null).apply {
                    addView(Prok.titleRow(this@MarketActivity, "Réf. " + inv.reference, inv.text, if (inv.paid) Prok.Tone.OK else if (inv.open) Prok.Tone.WARN else Prok.Tone.MUTED))
                    addView(Prok.muted(this@MarketActivity, MarketView.invoiceLine(inv, now) + " · " + inv.service + (if (inv.open) " · appuyez pour payer" else ""), 4))
                })
            }
        }
    }

    private fun mineRow(l: MarketView.Listing, now: Long): View {
        val tone = when { l.live -> Prok.Tone.OK; l.needsPayment -> Prok.Tone.WARN; l.state == "AWAITING_REVIEW" -> Prok.Tone.BRAND; l.state == "REJECTED" || l.state == "HIDDEN" -> Prok.Tone.DANGER; else -> Prok.Tone.MUTED }
        val box = Prok.card(this)
        box.addView(Prok.titleRow(this, l.title, l.stateText, tone))
        box.addView(Prok.stat(this, l.priceText, 2))
        box.addView(Prok.muted(this, listOfNotNull(
            if (l.live) l.daysLeft(now).toString() + " j restants" else null,
            if (l.boosted) MarketView.SPONSORED_LABEL + " (" + l.boostZone + ")" else null).joinToString(" · ") +
            (if (l.reviewNote.isNotEmpty()) "\nModération : " + l.reviewNote else ""), 2))
        val buttons = ArrayList<View>()
        if (l.needsPayment) buttons += Prok.primary(this, "Payer") { chooseRail { r -> invoiceFlow { market.payListing(l.id, r) } } }
        buttons += Prok.secondary(this, "Voir") { openDetail(l.id) }
        if (l.canEdit) buttons += Prok.secondary(this, "Modifier") { editDialog(l) }
        if (l.canRenew) buttons += Prok.secondary(this, "Renouveler") {
            confirm("Renouveler 30 jours", MarketView.offerLine("POST") + "\nLa nouvelle période s'ajoute à la fin de l'actuelle une fois le paiement confirmé.") { chooseRail { r -> invoiceFlow { market.renew(l.id, r) } } }
        }
        if (l.canBoost) buttons += Prok.secondary(this, "Booster") {
            ask("Mettre en avant", "Quartier de la mise en avant (ex. Bacongo)") { zone ->
                if (zone.isEmpty()) toast("Un quartier est requis") else confirm("Booster", MarketView.offerLine("BOOST") + "\nL'annonce sera marquée « Sponsorisé ». Aucune garantie de vues.") { chooseRail { r -> invoiceFlow { market.boost(l.id, zone, r) } } }
            }
        }
        if (l.canWithdraw) buttons += Prok.ghost(this, "Retirer", danger = true) {
            confirm("Retirer l'annonce", "Elle ne sera plus visible. Aucun remboursement pour la période déjà servie.") { run { market.withdraw(l.id) } }
        }
        box.addView(Prok.actions(this, *buttons.toTypedArray()))
        return box
    }

    private fun editDialog(l: MarketView.Listing) {
        val title = EditText(this).apply { hint = "Titre"; setText(l.title) }
        val desc = EditText(this).apply { hint = "Description"; setText(l.description); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val price = EditText(this).apply { hint = "Prix (F)"; setText((l.priceCentimes / 100).toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        val hood = EditText(this).apply { hint = "Quartier"; setText(l.neighbourhood) }
        val pickup = EditText(this).apply { hint = "Lieu de rencontre"; setText(l.pickupOptions) }
        AlertDialog.Builder(this).setTitle("Modifier").setMessage("Changer le titre ou la description renvoie l'annonce en modération, sans prolonger sa durée. Le prix et le lieu changent librement.").setView(form(title, desc, price, hood, pickup))
            .setPositiveButton("Enregistrer") { _, _ ->
                run { market.editListing(l.id, title.text.toString().trim(), desc.text.toString().trim(), l.category,
                    (price.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L) * 100, l.condition, hood.text.toString().trim(), pickup.text.toString().trim()) }
            }.setNegativeButton("Annuler", null).show()
    }

    // ---- packages ---------------------------------------------------------------------------------

    private fun openPackages() {
        show(R.id.mkPackages)
        io.execute {
            val s = market.me()
            runOnUiThread {
                seller = s
                findViewById<TextView>(R.id.mkPackagesMine).text = if (s == null || s.packages.isEmpty()) "Aucun forfait actif."
                    else s.packages.joinToString("\n") { it.line + " · valable jusqu'au " + dateFmt.format(Date(it.validUntil)) }
            }
        }
    }

    private fun buyPackage(kind: String) {
        confirm(if (kind == "STOREFRONT") "Vitrine professionnelle" else "Forfait 5 publications",
            MarketView.offerLine(kind) + "\n\nUne facture avec référence sera créée ; le service démarre quand le trésorier confirme la réception.") {
            chooseRail { r -> invoiceFlow { market.buyPackage(kind, r) } }
        }
    }

    /** Any call that answers with an invoice ends on the payment screen. */
    private fun invoiceFlow(block: () -> MarketSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { MarketSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread {
                if (!out.ok) { toast(out.message); return@runOnUiThread }
                val inv = MarketView.invoiceOf(out.text)
                if (inv != null) openPay(inv) else { toast(out.message); openMine() }
            }
        }
    }

    private fun chooseRail(then: (String) -> Unit) {
        AlertDialog.Builder(this).setTitle("Payer avec").setItems(arrayOf("MTN MoMo", "Airtel Money")) { _, i ->
            rail = if (i == 0) "MTN" else "AIRTEL"; then(rail)
        }.setNegativeButton("Annuler", null).show()
    }

    // ---- polling ------------------------------------------------------------------------------------

    private fun startPolling(tick: () -> Unit) {
        stopPolling()
        val r = object : Runnable { override fun run() { tick(); ui.postDelayed(this, POLL_MS) } }
        poller = r
        ui.post(r)
    }

    private fun stopPolling() { poller?.let { ui.removeCallbacks(it) }; poller = null }

    // ---- small helpers ---------------------------------------------------------------------------

    private fun show(pane: Int) {
        for (p in panes) findViewById<View>(p).visibility = if (p == pane) View.VISIBLE else View.GONE
        if (pane != R.id.mkChat && pane != R.id.mkPay) stopPolling()
        Prok.segment(findViewById(R.id.btnMkBrowse), pane == R.id.mkBrowse || pane == R.id.mkDetail)
        Prok.segment(findViewById(R.id.btnMkSell), pane == R.id.mkSell)
        Prok.segment(findViewById(R.id.btnMkMine), pane == R.id.mkMine || pane == R.id.mkPackages || pane == R.id.mkPay)
        findViewById<TextView>(R.id.mkFooter).text = "Identité prok-" + market.myId.take(8) + (if (market.configured) "" else " · Brain non configuré")
        findViewById<View>(R.id.mkScroll).scrollTo(0, 0)
    }

    private fun visible(id: Int): Boolean = findViewById<View>(id).visibility == View.VISIBLE
    private fun spinner(id: Int, items: List<String>) { findViewById<PickerView>(id).items = items }

    /** Dialog fields with the screen's own margins. */
    private fun form(vararg fields: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; val p = Prok.dp(context, 20); setPadding(p, Prok.dp(context, 8), p, 0)
        for (f in fields) addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Prok.dp(context, 8) })
    }

    private fun run(block: () -> MarketSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { MarketSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread { toast(out.message); if (visible(R.id.mkMine)) openMine() else if (visible(R.id.mkDetail)) current?.let { openDetail(it.id) } }
        }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Oui") { _, _ -> onYes() }.setNegativeButton("Non", null).show()
    }

    private fun ask(title: String, hint: String, onText: (String) -> Unit) {
        val input = EditText(this).apply { this.hint = hint }
        AlertDialog.Builder(this).setTitle(title).setView(form(input)).setPositiveButton("OK") { _, _ -> onText(input.text.toString().trim()) }.setNegativeButton("Annuler", null).show()
    }

    private fun copy(s: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("référence", s))
        toast("Référence copiée")
    }

    private fun text(id: Int): String = findViewById<TextView>(id).text.toString().trim()
    private fun digits(id: Int): Long = text(id).filter { it.isDigit() }.toLongOrNull() ?: 0L
    private fun status(s: String) { findViewById<TextView>(R.id.mkStatus).text = s }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQ_PHOTO = 4101
        const val POLL_MS = 20_000L
    }
}
