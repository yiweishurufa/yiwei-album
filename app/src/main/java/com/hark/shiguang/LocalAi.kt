package com.hark.shiguang

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 1.0.1 #8: on-device fallback classification with ML Kit Image Labeling — the BUNDLED artifact
 * `com.google.mlkit:image-labeling` (model inside the APK, ~5.7 MB, works offline, no Google Play services;
 * NOT `com.google.android.gms:play-services-mlkit-image-labeling`, which downloads the model through Play).
 *
 * Used when no model is configured, or when every model is cooled down / over budget, so classification never
 * stops. Results are marked 本地 ([AiLabel.local]) and are re-done by a model once one is available again.
 * The 400+ English labels of the base model (developers.google.com/ml-kit/vision/image-labeling/label-map)
 * are mapped onto the app's categories ([AiClient.CATS]) and translated into Chinese tags for search.
 */
object LocalAi {
    /** 「本地识别（离线）」 switch, default on. */
    var enabled by mutableStateOf(Store.getStr("ai.local", "1") == "1")
        private set
    fun set(on: Boolean) { enabled = on; Store.putStr("ai.local", if (on) "1" else "0") }

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())
    }

    /** label → category. Only labels that say something about the category are listed. */
    private val CAT: Map<String, String> = HashMap<String, String>().apply {
        fun add(cat: String, vararg ls: String) { for (l in ls) this[l] = cat }
        add("人像", "Selfie", "Smile", "Beard", "Moustache", "Eyelash", "Hair", "Bangs", "Lipstick", "Baby", "Bride", "Groom", "Dude", "Model", "Laugh", "Skin", "Mouth", "Ear", "Veil", "Gown", "Tuxedo", "Person")
        add("合照", "Crowd", "Team", "Crew", "Community", "Party", "Marriage", "Graduation", "Prom", "Class", "Army", "Militia", "Grandparent", "Interaction", "Picnic", "Event")
        add("美食", "Food", "Cuisine", "Meal", "Lunch", "Supper", "Bento", "Cheeseburger", "Hot dog", "Fast food", "Pizza", "Sushi", "Cake", "Cookie", "Bread", "Pie", "Fruit", "Vegetable",
            "Gelato", "Icing", "Juice", "Coffee", "Cappuccino", "Cola", "Wine", "Alcohol", "Couscous", "Pho", "Pasteles", "Tableware", "Cutlery", "Saucer", "Cup", "Eating", "Menu", "Steaming", "Cookware and bakeware")
        add("宠物", "Pet", "Dog", "Cat", "Bird", "Shetland sheepdog", "Cairn terrier", "Basset hound", "Dalmatian", "Cavalier", "Shikoku", "Ragdoll", "Sphynx", "Pixie-bob", "Gerbil", "Turtle", "Duck",
            "Horse", "Fur", "Penguin", "Butterfly", "Insect", "Bear", "Cattle", "Bull", "Herd", "Seal", "Crocodile", "Waterfowl", "Pomacentridae", "Aquarium")
        add("风景", "Sky", "Sunset", "Mountain", "Beach", "Lake", "River", "Waterfall", "Glacier", "Iceberg", "Desert", "Dune", "Canyon", "Cliff", "Cave", "Forest", "Jungle", "Field", "Prairie", "Rainbow",
            "Fog", "Storm", "Volcano", "Swamp", "Sand", "Rock", "Ice", "Icicle", "Reef", "Underwater", "Himalayan", "Park", "Farm", "Ranch", "Vacation", "Safari", "Camping", "Backpacking", "Pier", "Dam")
        add("建筑", "Building", "Tower", "Church", "Cathedral", "Temple", "Mosque", "Castle", "Palace", "Monument", "Statue", "Museum", "Ruins", "Lighthouse", "Roof", "Barn", "Stairs", "Wall", "Brick", "Tile", "Factory")
        add("城市", "Skyline", "Skyscraper", "Road", "Asphalt", "Infrastructure", "Bridge", "Construction", "Ferris wheel", "Stadium", "Playground", "Carnival", "Circus")
        add("夜景", "Fireworks", "Sparkler", "Neon", "Moon", "Star", "Nebula", "Aurora", "Comet", "Lightning", "Nightclub", "Bonfire", "Fire", "Space")
        add("植物", "Flower", "Plant", "Flora", "Petal", "Flowerpot", "Garden", "Twig", "Branch", "Wreath", "Centrepiece")
        add("交通", "Car", "Bus", "Train", "Airplane", "Airliner", "Aircraft", "Aviation", "Helicopter", "Bicycle", "Motorcycle", "Unicycle", "Boat", "Sailboat", "Speedboat", "Kayak", "Canoe", "Skiff",
            "Brig", "Frigate", "Clipper", "Submarine", "Van", "Vehicle", "Tractor", "Wheel", "Tire", "Windshield", "Bumper", "Rickshaw", "Odometer", "Track", "Rocket", "Shipwreck")
        add("运动", "Sports", "Soccer", "Running", "Swimming", "Skiing", "Surfing", "Cycling", "Badminton", "Rugby", "Marathon", "Racing", "Race", "Gymnastics", "Skateboard", "Skateboarder", "Snowboarding",
            "Competition", "Pitch", "Softball", "Archery", "Rowing", "Rafting", "Windsurfing", "Wakeboarding", "Waterskiing", "Scuba diving", "Snorkeling", "Curling", "Boxer", "Polo", "Rodeo", "Bullfighting",
            "Sledding", "Tubing", "Longboard", "Surfboard", "Jersey", "Helmet", "Fishing", "Dance", "Muscle", "Balance", "Pool")
        add("文档", "Paper", "Receipt", "Newspaper", "News", "Passport", "Poster", "Whiteboard", "Blackboard", "Comics", "Presentation", "Money")
        add("截图", "Screenshot", "Web page", "Icon")
    }

    /** Chinese words for the tags (search is in Chinese). Labels not listed are dropped from the tags. */
    private val ZH: Map<String, String> = mapOf(
        "Selfie" to "自拍", "Smile" to "微笑", "Beard" to "胡子", "Baby" to "宝宝", "Bride" to "新娘", "Groom" to "新郎", "Laugh" to "笑", "Person" to "人", "Hair" to "头发", "Veil" to "婚纱", "Gown" to "礼服",
        "Crowd" to "人群", "Team" to "团队", "Party" to "聚会", "Marriage" to "婚礼", "Graduation" to "毕业", "Prom" to "舞会", "Class" to "课堂", "Picnic" to "野餐", "Event" to "活动", "Grandparent" to "老人",
        "Food" to "美食", "Cuisine" to "菜肴", "Meal" to "饭菜", "Lunch" to "午餐", "Supper" to "晚餐", "Bento" to "便当", "Cheeseburger" to "汉堡", "Hot dog" to "热狗", "Fast food" to "快餐", "Pizza" to "披萨",
        "Sushi" to "寿司", "Cake" to "蛋糕", "Cookie" to "饼干", "Bread" to "面包", "Pie" to "派", "Fruit" to "水果", "Vegetable" to "蔬菜", "Gelato" to "冰淇淋", "Juice" to "果汁", "Coffee" to "咖啡",
        "Cappuccino" to "咖啡", "Cola" to "可乐", "Wine" to "葡萄酒", "Alcohol" to "酒", "Pho" to "米粉", "Menu" to "菜单", "Cup" to "杯子",
        "Pet" to "宠物", "Dog" to "狗", "Cat" to "猫", "Bird" to "鸟", "Horse" to "马", "Penguin" to "企鹅", "Butterfly" to "蝴蝶", "Insect" to "昆虫", "Bear" to "熊", "Cattle" to "牛", "Bull" to "牛",
        "Duck" to "鸭子", "Turtle" to "乌龟", "Seal" to "海豹", "Aquarium" to "水族馆", "Fur" to "毛",
        "Sky" to "天空", "Sunset" to "日落", "Mountain" to "山", "Beach" to "海滩", "Lake" to "湖", "River" to "河", "Waterfall" to "瀑布", "Glacier" to "冰川", "Iceberg" to "冰山", "Desert" to "沙漠",
        "Dune" to "沙丘", "Canyon" to "峡谷", "Cliff" to "悬崖", "Cave" to "山洞", "Forest" to "森林", "Jungle" to "丛林", "Field" to "田野", "Prairie" to "草原", "Rainbow" to "彩虹", "Fog" to "雾",
        "Storm" to "暴风雨", "Volcano" to "火山", "Sand" to "沙子", "Rock" to "岩石", "Ice" to "冰", "Reef" to "珊瑚礁", "Underwater" to "水下", "Park" to "公园", "Farm" to "农场", "Vacation" to "度假",
        "Camping" to "露营", "Pier" to "码头", "Dam" to "大坝", "Snow" to "雪",
        "Building" to "建筑", "Tower" to "塔", "Church" to "教堂", "Cathedral" to "大教堂", "Temple" to "寺庙", "Mosque" to "清真寺", "Castle" to "城堡", "Palace" to "宫殿", "Monument" to "纪念碑",
        "Statue" to "雕像", "Museum" to "博物馆", "Ruins" to "遗迹", "Lighthouse" to "灯塔", "Roof" to "屋顶", "Stairs" to "楼梯", "Factory" to "工厂",
        "Skyline" to "天际线", "Skyscraper" to "摩天楼", "Road" to "道路", "Bridge" to "桥", "Construction" to "工地", "Ferris wheel" to "摩天轮", "Stadium" to "体育场", "Playground" to "游乐场", "Carnival" to "嘉年华",
        "Fireworks" to "烟花", "Sparkler" to "烟花", "Neon" to "霓虹灯", "Moon" to "月亮", "Star" to "星星", "Aurora" to "极光", "Lightning" to "闪电", "Bonfire" to "篝火", "Fire" to "火", "Nightclub" to "酒吧",
        "Flower" to "花", "Plant" to "植物", "Flora" to "植物", "Petal" to "花瓣", "Flowerpot" to "盆栽", "Garden" to "花园", "Branch" to "树枝",
        "Car" to "汽车", "Bus" to "公交车", "Train" to "火车", "Airplane" to "飞机", "Airliner" to "飞机", "Aircraft" to "飞机", "Helicopter" to "直升机", "Bicycle" to "自行车", "Motorcycle" to "摩托车",
        "Boat" to "船", "Sailboat" to "帆船", "Speedboat" to "快艇", "Kayak" to "皮划艇", "Canoe" to "独木舟", "Van" to "面包车", "Vehicle" to "车", "Tractor" to "拖拉机", "Rocket" to "火箭",
        "Sports" to "运动", "Soccer" to "足球", "Running" to "跑步", "Swimming" to "游泳", "Skiing" to "滑雪", "Surfing" to "冲浪", "Cycling" to "骑行", "Badminton" to "羽毛球", "Marathon" to "马拉松",
        "Racing" to "赛车", "Gymnastics" to "体操", "Skateboard" to "滑板", "Snowboarding" to "单板滑雪", "Fishing" to "钓鱼", "Dance" to "跳舞", "Pool" to "泳池", "Scuba diving" to "潜水", "Snorkeling" to "浮潜",
        "Paper" to "纸张", "Receipt" to "小票", "Newspaper" to "报纸", "Passport" to "护照", "Poster" to "海报", "Whiteboard" to "白板", "Blackboard" to "黑板", "Comics" to "漫画", "Money" to "钱",
        "Screenshot" to "截图", "Web page" to "网页",
        "Christmas" to "圣诞", "Concert" to "演唱会", "Musician" to "乐手", "Musical instrument" to "乐器", "Piano" to "钢琴", "Toy" to "玩具", "Stuffed toy" to "玩偶", "Lego" to "乐高",
        "Balloon" to "气球", "Umbrella" to "雨伞", "Sunglasses" to "墨镜", "Glasses" to "眼镜", "Hat" to "帽子", "Dress" to "裙子", "Jeans" to "牛仔裤", "Shoe" to "鞋", "Sneakers" to "运动鞋",
        "Handbag" to "手提包", "Necklace" to "项链", "Ring" to "戒指", "Kitchen" to "厨房", "Bedroom" to "卧室", "Bathroom" to "浴室", "Room" to "房间", "Couch" to "沙发", "Desk" to "书桌",
        "Computer" to "电脑", "Mobile phone" to "手机", "Television" to "电视", "Clock" to "时钟", "Bench" to "长椅", "Flag" to "旗帜", "Tattoo" to "纹身", "Love" to "爱心", "Heart" to "爱心",
    )

    private val SCREENSHOT_NAME = Regex("screenshot|screen_shot|screencap|截屏|截图|屏幕截图", RegexOption.IGNORE_CASE)

    /** Labels one ≤512 px JPEG on the phone. [name] helps with screenshots (file names). */
    suspend fun classify(jpeg: ByteArray, name: String = ""): AiLabel = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw Exception("无法解码图片")
        val labels = try {
            Tasks.await(labeler.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
        } finally { /* bitmap is small; let GC take it */ }
        val score = HashMap<String, Float>()
        for (l in labels) CAT[l.text]?.let { c -> score[c] = (score[c] ?: 0f) + l.confidence }
        var cat = score.maxByOrNull { it.value }?.key ?: "其他"
        // people: a few strong person labels beat weak scenery
        if (cat != "合照" && (score["人像"] ?: 0f) >= 0.9f && (score["人像"] ?: 0f) >= (score[cat] ?: 0f) * 0.8f) cat = "人像"
        if (SCREENSHOT_NAME.containsMatchIn(name)) cat = "截图"
        else if (cat in setOf("风景", "建筑", "城市", "其他") && dark(bmp)) cat = "夜景"
        val tags = labels.sortedByDescending { it.confidence }.mapNotNull { ZH[it.text] }.distinct().take(6)
            .let { if (cat == "夜景" && "夜景" !in it) listOf("夜景") + it.take(5) else it }
        AiLabel(cat, tags, "本地识别" + if (tags.isNotEmpty()) "：" + tags.take(3).joinToString("、") else "", -1, local = true, model = "本地")
    }

    /** Mean luminance of a sampled grid < 55/255 → taken at night. */
    private fun dark(b: Bitmap): Boolean {
        val n = 24; var sum = 0L; var cnt = 0
        for (y in 0 until n) for (x in 0 until n) {
            val p = b.getPixel((x * (b.width - 1)) / (n - 1), (y * (b.height - 1)) / (n - 1))
            sum += (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000; cnt++
        }
        return cnt > 0 && sum / cnt < 55
    }
}
