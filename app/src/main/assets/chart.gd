extends Node3D
## Interactive 3D dental chart. Educational procedural dentition; clinical records remain in the
## native host. Each anatomical slot has a stable FDI body, including recorded missing slots.
## Bridge contract (unchanged): chart(), selectTooth(key), selectSurface(key, surface), closeChart().

# --- Palette ----------------------------------------------------------------------------------
const ENAMEL := Color("fff7e8")
const UNKNOWN := Color("dfe6eb")
const GUM := Color("e8979d")
const TEAL := Color("0f766e")
const TEAL_LIGHT := Color("14b8a6")
const TEAL_TINT := Color("ccfbf1")
const BLUE := Color("2563eb")
const AMBER := Color("f59e0b")
const INK := Color("0f172a")
const MUTED := Color("5b6b7a")
const HAIRLINE := Color("e3e9ee")
const SHEET := Color(1, 1, 1, 0.97)
const LOST := ["missing", "extracted", "exfoliated", "congenitally_absent"]
const SURFACES := ["M", "D", "F", "L", "I", "O"]

# --- Arch geometry ----------------------------------------------------------------------------
# ---- Shared tooth geometry spec v2 ------------------------------------------------------------
# Teeth3DView.kt (profile preview) ports these constants and functions line for line so both views
# show the same model. Change them together.
const ARCH_A := 3.5
const ARCH_B := 4.1
const ARCH_SHIFT := 1.9
const COLLAR := 1.30
const DENTIN := Color("ead7b2")
const TRANSLUCENT := Color("d3e0ea")
const FINDING := Color("f59e0b")
const RESTORATION := Color("4f7fe8")
const TREATED := Color("60a5fa")
const GUM_DEEP := Color("d4848b")
const SULCUS := Color("c46d75")
const PALATE := Color("e3a0a5")
const CEMENTUM := Color("e6d2a8")
const APEX := Color("c9ad7c")
const FISSURE := Color("c4a57a")

# --- Camera presets: [pitch, yaw, distance, arch filter] --------------------------------------
const VIEWS := {
	"Front": [0.0, 0.0, 12.0, "Both"],
	"Upper": [-1.22, 0.0, 12.0, "Upper"],
	"Lower": [1.22, 0.0, 12.0, "Lower"],
	"Left": [0.0, -1.2, 12.5, "Both"],
	"Right": [0.0, 1.2, 12.5, "Both"],
}

var bridge
var chart: Dictionary = {}
var teeth: Dictionary = {}
var rig := Node3D.new()
var camera := Camera3D.new()
var upper := Node3D.new()
var lower := Node3D.new()
var gum_materials: Array[StandardMaterial3D] = []

# Camera: target values are set by input; current values ease toward them every frame.
var target := Vector3.ZERO
var yaw := 0.0
var pitch := 0.0
var distance := 12.0
var cur_target := Vector3.ZERO
var cur_yaw := -0.6
var cur_pitch := 0.0
var cur_distance := 16.0
var yaw_velocity := 0.0
var pulse := 0.0

var selected := ""
var selected_surface := ""
var pending_pick := Vector2(-1, -1)
var fingers: Dictionary = {}
var touch_origin := Vector2.ZERO
var moved := false
var last_tap_ms := 0
var selected_halo: MeshInstance3D
var show_labels := true
var show_roots := false
var arch_filter := "Both"
var current_view := "Front"
var input_valid := true
var read_only := false
var notation := "palmer"
var loading_overlay: ColorRect

# UI
var canvas := CanvasLayer.new()
var sheet := PanelContainer.new()
var sheet_idle := VBoxContainer.new()
var sheet_detail := VBoxContainer.new()
var badge_label := Label.new()
var name_label := Label.new()
var status_label := Label.new()
var chip_row := HFlowContainer.new()
var surface_pad := GridContainer.new()
var surface_hint := Label.new()
var assess_button := Button.new()
var view_buttons: Dictionary = {}
var arch_buttons: Dictionary = {}
var toggle_buttons: Dictionary = {}
var surface_buttons: Dictionary = {}

func mat(color: Color, roughness: float = 0.26) -> StandardMaterial3D:
	var result := StandardMaterial3D.new()
	result.albedo_color = color
	result.roughness = roughness
	result.metallic_specular = 0.58
	result.rim_enabled = true
	result.rim = 0.28
	result.rim_tint = 0.6
	result.cull_mode = BaseMaterial3D.CULL_DISABLED
	if color.a < 1.0:
		result.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	return result

func _ready() -> void:
	chart = {"schemaVersion": 2, "dentition": "permanent", "teeth": {}}
	if Engine.has_singleton("DentalBridge"):
		bridge = Engine.get_singleton("DentalBridge")
		var parsed = JSON.parse_string(bridge.chart())
		if parsed is Dictionary and parsed.get("schemaVersion", 0) == 2 and parsed.get("teeth", null) is Dictionary:
			chart = parsed
			read_only = chart.get("readOnly", false)
			notation = str(chart.get("notation", "palmer"))
		else:
			input_valid = false
	add_child(rig)
	upper.name = "UpperArch"
	lower.name = "LowerArch"
	rig.add_child(upper)
	rig.add_child(lower)
	add_child(camera)
	camera.fov = 40
	camera.near = 0.05
	build_environment()
	build_ui()
	show_loading()
	update_camera()
	set_process_unhandled_input(false)
	# Let the first (overlay) frame present before building meshes, then fade the model in.
	await get_tree().process_frame
	await get_tree().process_frame
	if input_valid:
		build_inventory()
	toggle_buttons["123"].button_pressed = show_labels
	set_process_unhandled_input(input_valid)
	var fade := create_tween()
	fade.tween_property(loading_overlay, "color:a", 0.0, 0.45).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	fade.parallel().tween_property(loading_overlay.get_child(0), "modulate:a", 0.0, 0.25)
	fade.tween_callback(loading_overlay.queue_free)

func show_loading() -> void:
	var layer := CanvasLayer.new()
	layer.layer = 10
	add_child(layer)
	loading_overlay = ColorRect.new()
	loading_overlay.color = Color("f5fbfc")
	loading_overlay.mouse_filter = Control.MOUSE_FILTER_IGNORE
	loading_overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	layer.add_child(loading_overlay)
	var text := label("Preparing 3D chart…", 30, TEAL, true)
	text.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	text.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	text.grow_horizontal = Control.GROW_DIRECTION_BOTH
	text.grow_vertical = Control.GROW_DIRECTION_BOTH
	loading_overlay.add_child(text)

func build_environment() -> void:
	var world := WorldEnvironment.new()
	var environment := Environment.new()
	var sky_material := ProceduralSkyMaterial.new()
	sky_material.sky_top_color = Color("cfe9ee")
	sky_material.sky_horizon_color = Color("f5fbfc")
	sky_material.ground_horizon_color = Color("f5fbfc")
	sky_material.ground_bottom_color = Color("dde9ee")
	var sky := Sky.new()
	sky.sky_material = sky_material
	environment.background_mode = Environment.BG_SKY
	environment.sky = sky
	environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.ambient_light_color = Color("eaf2f6")
	environment.ambient_light_energy = 0.62
	world.environment = environment
	add_child(world)
	# Studio lighting: warm key, cool fill, soft back light for a clean rim on enamel.
	for setup in [[Vector3(-5, 8, 7), Color("fff6ea"), 0.85], [Vector3(7, 2, 5), Color("e6f4ff"), 0.45], [Vector3(0, 3, -8), Color("ffffff"), 0.5], [Vector3(0, -6, 4), Color("f0f6ff"), 0.3]]:
		var light := DirectionalLight3D.new()
		add_child(light)
		light.position = setup[0]
		light.look_at(Vector3.ZERO)
		light.light_color = setup[1]
		light.light_energy = setup[2]
		light.sky_mode = DirectionalLight3D.SKY_MODE_LIGHT_ONLY

# ==============================================================================================
# Dentition
# ==============================================================================================

func build_inventory() -> void:
	var dentition: String = chart.get("dentition", "permanent")
	if dentition not in ["primary", "permanent", "mixed"]:
		input_valid = false
		return
	if dentition != "primary":
		build_jaw(1, false, upper, 1.0, 0.0)
		build_jaw(4, false, lower, 0.96, 0.0)
		upper.add_child(gum_band(COLLAR, 1.0, 0.0, dentition == "mixed", 8))
		lower.add_child(gum_band(-COLLAR, 0.96, 0.0, dentition == "mixed", 8))
	if dentition != "permanent":
		# Mixed dentition keeps two labeled inventories: primary slots occupy an inner
		# diagrammatic row rather than replacing or silently overlapping successors.
		var factor := 0.70 if dentition == "mixed" else 0.92
		build_jaw(5, true, upper, factor, 0.0)
		build_jaw(8, true, lower, factor * 0.96, 0.0)
		if dentition == "primary":
			upper.add_child(gum_band(COLLAR * 0.92, factor * 0.78, 0.0, false, 5))
			lower.add_child(gum_band(-COLLAR * 0.92, factor * 0.75, 0.0, false, 5))

func build_jaw(first_quadrant: int, primary: bool, parent: Node3D, factor: float, shift: float) -> void:
	var max_position := 5 if primary else 8
	var is_upper := first_quadrant in [1, 5]
	var sign_y := -1.0 if is_upper else 1.0
	var collar_y := COLLAR if is_upper else -COLLAR
	if dentition_is_primary_only(): collar_y *= 0.92
	var spatial_factor := factor * (0.78 if primary else 1.0)
	for side in range(2):
		var quadrant := first_quadrant + side if is_upper else first_quadrant - side
		var patient_right := quadrant in [1, 4, 5, 8]
		var side_sign := -1.0 if patient_right else 1.0
		for position in range(1, max_position + 1):
			var key := str(quadrant) + str(position)
			var state: Dictionary = chart.get("teeth", {}).get(key, {})
			var theta := (float(position) - 0.45) / float(max_position) * 1.62
			var body := StaticBody3D.new()
			body.name = "Tooth_" + key
			body.position = Vector3(sin(theta) * ARCH_A * side_sign * spatial_factor, collar_y, (cos(theta) * ARCH_B - ARCH_SHIFT) * spatial_factor + shift)
			var facial := Vector3(sin(theta) * side_sign / ARCH_A, 0, cos(theta) / ARCH_B).normalized()
			# Local +Z faces the lips/cheeks (facial), local +X runs mesial on the patient's right.
			body.basis = Basis.looking_at(-facial, Vector3.UP)
			body.set_meta("toothKey", key)
			body.set_meta("signY", sign_y)
			body.set_meta("primary", primary)
			body.set_meta("mesialSign", 1.0 if patient_right else -1.0)
			parent.add_child(body)
			var kind := "incisor" if position <= 2 else "canine" if position == 3 else "molar" if primary or position >= 6 else "premolar"
			var dimensions := Vector3(0.72, 0.96, 0.39)
			if position == 2: dimensions.x = 0.62
			if kind == "canine": dimensions = Vector3(0.64, 1.08, 0.61)
			if kind == "premolar": dimensions = Vector3(0.71, 0.86, 0.80)
			if kind == "molar": dimensions = Vector3(0.88 if position == 8 else 0.96, 0.80, 0.98)
			dimensions *= factor
			body.set_meta("dimensions", dimensions)
			var presence: String = state.get("presence", "unknown")
			var replacement: String = state.get("replacement", "none")
			var absent := presence in LOST
			if not absent or replacement not in ["none", ""]:
				var color := ENAMEL if state.get("assessment", "not_assessed") == "assessed" else UNKNOWN
				if presence == "unerupted": color = Color("d5dee5")
				if absent: color = Color("e9eef2") if replacement == "implant" else Color("f1ecf8")
				if state.get("treated", false): color = color.lerp(TREATED, 0.22)
				if not state.get("findings", []).is_empty(): color = color.lerp(FINDING, 0.28)
				var crown := MeshInstance3D.new()
				crown.name = "Crown"
				crown.mesh = crown_mesh(kind, dimensions, sign_y, surface_colors(state, color, position <= 3), 1.0 if patient_right else -1.0)
				var crown_material := mat(Color(1, 1, 1, 0.5) if presence == "unerupted" else Color.WHITE, 0.18)
				crown_material.vertex_color_use_as_albedo = true
				apply_enamel_texture(crown_material)
				crown.material_override = crown_material
				body.set_meta("crownMaterial", crown_material)
				body.add_child(crown)
				if replacement == "implant": add_implant(body, dimensions, sign_y)
				else: add_roots(body, kind, dimensions, sign_y, is_upper, position, primary, 1.0 if patient_right else -1.0)
				add_state_markers(body, state, dimensions, sign_y)
			else:
				add_absent_marker(body, dimensions, sign_y)
			var collision := CollisionShape3D.new()
			var box := BoxShape3D.new()
			box.size = dimensions * Vector3(1.08, 1.0, 1.08)
			collision.position.y = sign_y * dimensions.y * 0.45
			collision.shape = box
			body.add_child(collision)
			var tooth_label := Label3D.new()
			tooth_label.name = "SlotLabel"
			tooth_label.text = notation_short(key)
			tooth_label.font_size = 40
			tooth_label.outline_size = 12
			tooth_label.pixel_size = 0.0042
			tooth_label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
			tooth_label.modulate = Color("334155")
			tooth_label.outline_modulate = Color(1, 1, 1, 0.95)
			tooth_label.position = Vector3(0, sign_y * (dimensions.y + 0.24), dimensions.z * 0.35)
			tooth_label.visible = show_labels
			body.add_child(tooth_label)
			teeth[key] = body

func dentition_is_primary_only() -> bool:
	return chart.get("dentition", "permanent") == "primary"

## Per-surface colours: recorded findings tint amber, restorations blue (same rule as the 2D chart and profile).
func surface_colors(state: Dictionary, base: Color, anterior: bool) -> Dictionary:
	var colors := {"base": base}
	var surfaces: Dictionary = state.get("surfaces", {})
	for surface in ["M", "D", "F", "L", "I" if anterior else "O"]:
		var observation: Dictionary = surfaces.get(surface, {})
		var color := base
		if not observation.get("restorations", []).is_empty(): color = base.lerp(RESTORATION, 0.72)
		if not observation.get("findings", []).is_empty(): color = base.lerp(FINDING, 0.78)
		colors["top" if surface in ["I", "O"] else surface] = color
	return colors

## Enamel shading baked into vertex colours: warmer dentin at the neck, soft occlusion at the gumline
## and translucent incisal edges on front teeth.
func enamel(color: Color, level: float, kind: String) -> Color:
	var result := color.lerp(DENTIN, 0.30 * pow(1.0 - level, 2.0))
	if kind in ["incisor", "canine"]: result = result.lerp(TRANSLUCENT, 0.42 * pow(level, 5.0))
	var occlusion := 0.80 + 0.20 * clampf(level / 0.35, 0.0, 1.0)
	return Color(result.r * occlusion, result.g * occlusion, result.b * occlusion, 1.0)

## Natural enamel is never uniform: a stable per-vertex brightness variation (same hash as the profile preview).
func mottle(color: Color, p: Vector3) -> Color:
	var n := fposmod(sin(p.x * 12.9898 + p.y * 78.233 + p.z * 37.719) * 43758.5453, 1.0)
	var f := 0.975 + 0.05 * n
	return Color(color.r * f, color.g * f, color.b * f, color.a)

## Darker, slightly stained occlusal fissures on premolars and molars.
func fissure_stain(color: Color, kind: String, u: float, v: float) -> Color:
	if kind not in ["premolar", "molar"]: return color
	var groove := exp(-v * v * 70.0)
	if kind == "molar": groove = max(groove, exp(-u * u * 70.0) * 0.8)
	return color.lerp(FISSURE, 0.50 * groove)

## Procedural enamel micro-relief (perikymata-like streaks) and gloss variation. Generated once.
var enamel_normal: NoiseTexture2D
var enamel_roughness: NoiseTexture2D
func apply_enamel_texture(material: StandardMaterial3D) -> void:
	if enamel_normal == null:
		var noise := FastNoiseLite.new()
		noise.noise_type = FastNoiseLite.TYPE_SIMPLEX_SMOOTH
		noise.frequency = 0.045
		noise.fractal_octaves = 3
		enamel_normal = NoiseTexture2D.new()
		enamel_normal.width = 256
		enamel_normal.height = 256
		enamel_normal.seamless = true
		enamel_normal.as_normal_map = true
		enamel_normal.bump_strength = 1.6
		enamel_normal.noise = noise
		var gloss := FastNoiseLite.new()
		gloss.frequency = 0.02
		enamel_roughness = NoiseTexture2D.new()
		enamel_roughness.width = 128
		enamel_roughness.height = 128
		enamel_roughness.seamless = true
		enamel_roughness.noise = gloss
	material.normal_enabled = true
	material.normal_texture = enamel_normal
	material.normal_scale = 0.35
	material.roughness_texture = enamel_roughness
	material.roughness = 0.32
	# Stretch the noise horizontally around the crown: fine bands like perikymata.
	material.uv1_scale = Vector3(3.0, 9.0, 1.0)

func side_surface(angle: float, mesial_sign: float) -> String:
	var c := cos(angle)
	var s := sin(angle)
	if abs(s) >= abs(c): return "F" if s > 0 else "L"
	return "M" if (c > 0) == (mesial_sign > 0) else "D"

func crown_mesh(kind: String, size: Vector3, sign_y: float, colors: Dictionary = {}, mesial_sign: float = 1.0) -> ArrayMesh:
	var tool := SurfaceTool.new()
	tool.begin(Mesh.PRIMITIVE_TRIANGLES)
	var base: Color = colors.get("base", ENAMEL)
	var segments := 40
	var levels := 10
	var half_x := size.x * 0.5
	var half_z := size.z * 0.5
	var exponent := 2.8 if kind in ["premolar", "molar"] else 2.5
	for level in range(levels):
		var low := float(level) / float(levels)
		var high := float(level + 1) / float(levels)
		for segment in range(segments):
			var a := TAU * segment / segments
			var b := TAU * (segment + 1) / segments
			var p0 := crown_side(a, low, half_x, half_z, size.y, exponent, kind, sign_y)
			var p1 := crown_side(b, low, half_x, half_z, size.y, exponent, kind, sign_y)
			var p2 := crown_side(a, high, half_x, half_z, size.y, exponent, kind, sign_y)
			var p3 := crown_side(b, high, half_x, half_z, size.y, exponent, kind, sign_y)
			var face: Color = colors.get(side_surface((a + b) * 0.5, mesial_sign), base)
			var levels_of := [low, high, low, low, high, high]
			var corners := [p0, p2, p1, p1, p2, p3]
			var angles_of := [a, a, b, b, a, b]
			for i in range(6):
				var vertex: Vector3 = corners[i]
				tool.set_color(mottle(enamel(face, levels_of[i], kind), vertex))
				tool.set_uv(Vector2(angles_of[i] / TAU * 2.0, levels_of[i]))
				tool.set_normal(Vector3(vertex.x / (half_x * half_x), 0.09 * sign_y, vertex.z / (half_z * half_z)).normalized())
				tool.add_vertex(vertex)
	# The occlusal table is a continuous custom surface with cusp and fissure relief.
	for ring in range(8):
		for segment in range(segments):
			var points: Array[Vector3] = []
			for polar in [Vector2(float(ring) / 8.0, TAU * segment / segments), Vector2(float(ring + 1) / 8.0, TAU * segment / segments), Vector2(float(ring) / 8.0, TAU * (segment + 1) / segments), Vector2(float(ring + 1) / 8.0, TAU * (segment + 1) / segments)]:
				var edge := boundary(polar.y, half_x, half_z, exponent)
				var x: float = edge.x * polar.x * tip_width(kind)
				var z: float = edge.y * polar.x * tip_depth(kind)
				points.append(Vector3(x, top_height(kind, x / half_x, z / half_z) * size.y * sign_y, z))
			var top: Color = colors.get("top", base)
			for index in [0, 1, 2, 2, 1, 3]:
				var vertex := points[index]
				tool.set_color(mottle(fissure_stain(enamel(top, 1.0, kind), kind, vertex.x / half_x, vertex.z / half_z), vertex))
				tool.set_uv(Vector2(0.5 + vertex.x / size.x, 1.0 + vertex.z / size.z))
				var epsilon := 0.006
				var dx := (top_height(kind, (vertex.x + epsilon) / half_x, vertex.z / half_z) - top_height(kind, (vertex.x - epsilon) / half_x, vertex.z / half_z)) * size.y / (2.0 * epsilon)
				var dz := (top_height(kind, vertex.x / half_x, (vertex.z + epsilon) / half_z) - top_height(kind, vertex.x / half_x, (vertex.z - epsilon) / half_z)) * size.y / (2.0 * epsilon)
				tool.set_normal(Vector3(-dx, sign_y, -dz).normalized())
				tool.add_vertex(vertex)
	return tool.commit()

func boundary(angle: float, half_x: float, half_z: float, exponent: float) -> Vector2:
	var c := cos(angle)
	var s := sin(angle)
	return Vector2(sign(c) * pow(abs(c), 2.0 / exponent) * half_x, sign(s) * pow(abs(s), 2.0 / exponent) * half_z)

func tip_width(kind: String) -> float:
	return 0.88 if kind == "incisor" else 0.90

func tip_depth(kind: String) -> float:
	return 0.46 if kind == "incisor" else 0.80 if kind == "canine" else 0.88

func crown_side(angle: float, level: float, half_x: float, half_z: float, height: float, exponent: float, kind: String, sign_y: float) -> Vector3:
	var edge := boundary(angle, half_x, half_z, exponent)
	var belly := 0.76 + sin(level * PI * 0.85) * 0.26
	var width: float = lerpf(belly, tip_width(kind), pow(level, 4.0))
	var depth: float = lerpf(belly, tip_depth(kind), pow(level, 4.0))
	var x := edge.x * width
	var z := edge.y * depth
	# Front teeth: shovel-shaped lingual surface with a cingulum near the neck.
	if z < 0.0 and kind in ["incisor", "canine"]: z *= lingual_profile(level)
	var top := top_height(kind, edge.x * tip_width(kind) / half_x, edge.y * tip_depth(kind) / half_z)
	return Vector3(x, level * top * height * sign_y, z)

func lingual_profile(level: float) -> float:
	return 1.0 - 0.20 * sin(PI * level) * clampf((level - 0.12) / 0.25, 0.0, 1.0)

func top_height(kind: String, x: float, z: float) -> float:
	if kind == "incisor":
		return 0.96 - 0.10 * z * z + 0.012 * cos(x * 9.0)
	if kind == "canine":
		return 1.0 - 0.29 * abs(x) - 0.13 * abs(z)
	var base := 0.73
	var peaks := [Vector2(0, -0.47), Vector2(0, 0.47)] if kind == "premolar" else [Vector2(-0.43, -0.43), Vector2(0.43, -0.43), Vector2(-0.43, 0.43), Vector2(0.43, 0.43)]
	for peak in peaks:
		base += 0.18 * exp(-Vector2(x, z).distance_squared_to(peak) * 10.0)
	# An intersecting central fissure separates molar cusps without disconnected blobs.
	base -= 0.045 * exp(-z * z * 70.0)
	if kind == "molar": base -= 0.035 * exp(-x * x * 70.0)
	return base

## Interdental papilla weight for an arch angle: 1 between neighbouring teeth, 0 at a tooth centre.
func papilla(angle: float, max_position: int) -> float:
	return 0.5 + 0.5 * cos(TAU * (abs(angle) * float(max_position) / 1.62 - 0.05))

## Gingiva: a tube following the arch, wider over the molars, hugging the teeth with a scalloped
## margin whose papillae sit exactly between neighbouring teeth.
func gum_band(height: float, factor: float, shift: float, wide: bool, max_position: int = 8) -> MeshInstance3D:
	var tool := SurfaceTool.new()
	tool.begin(Mesh.PRIMITIVE_TRIANGLES)
	var sections := 140
	var rings := 18
	var radius_y := 0.24 * factor + 0.04
	var inner := -0.20 * factor if wide else 0.0
	var up := 1.0 if height > 0 else -1.0
	var center_y := height + up * (radius_y - 0.12)
	for section in range(sections):
		for ring in range(rings):
			var vertices: Array[Vector3] = []
			var normals: Array[Vector3] = []
			var colors: Array[Color] = []
			for point in [Vector2(section, ring), Vector2(section + 1, ring), Vector2(section, ring + 1), Vector2(section + 1, ring + 1)]:
				var angle: float = -1.70 + point.x / float(sections) * 3.40
				var cross_angle: float = point.y / float(rings) * TAU
				var radius_x: float = ((0.72 if wide else 0.44) + 0.20 * clampf(abs(angle) / 1.4, 0.0, 1.0)) * factor
				var outward := Vector3(sin(angle) / ARCH_A, 0, cos(angle) / ARCH_B).normalized()
				var center := Vector3(sin(angle) * ARCH_A * factor, center_y, (cos(angle) * ARCH_B - ARCH_SHIFT) * factor + shift)
				var toward_teeth: float = max(0.0, -sin(cross_angle) * up)
				var reach := 0.16 * factor * toward_teeth * papilla(angle, max_position)
				# Alveolar ridge / model base: the half away from the teeth is taller and squarer.
				var sn := sin(cross_angle)
				var cs := cos(cross_angle)
				var stretch := 1.0
				if sn * up > 0.0:
					sn = sign(sn) * pow(abs(sn), 0.45)
					cs = sign(cs) * pow(abs(cs), 0.7)
					stretch = 1.9
				var offset := outward * (cs * radius_x + inner) + Vector3.UP * (sn * radius_y * stretch - up * reach)
				vertices.append(center + offset)
				normals.append((outward * cs / radius_x + Vector3.UP * sn / (radius_y * stretch)).normalized())
				colors.append(GUM.lerp(GUM_DEEP, max(0.0, sin(cross_angle) * up) * 0.6).lerp(SULCUS, 0.35 * pow(toward_teeth, 4.0)))
			for index in [0, 2, 1, 1, 2, 3]:
				tool.set_color(colors[index])
				tool.set_normal(normals[index])
				tool.add_vertex(vertices[index])
	if up > 0.0: add_palate(tool, center_y, factor, shift, wide)
	var mesh := MeshInstance3D.new()
	mesh.name = "Gingiva"
	mesh.mesh = tool.commit()
	var gum_material := mat(Color.WHITE, 0.45)
	gum_material.vertex_color_use_as_albedo = true
	gum_material.rim = 0.35
	gum_material.rim_tint = 0.2
	mesh.material_override = gum_material
	gum_materials.append(gum_material)
	return mesh

## Domed palate closing the upper arch (visible in the upper occlusal view).
func add_palate(tool: SurfaceTool, center_y: float, factor: float, shift: float, wide: bool) -> void:
	var sections := 70
	var rows := 8
	# Interior of the arch only, rising gently (smoothstep) so no edge shows behind the molars.
	var peak := center_y + 0.42 * factor
	for section in range(sections):
		for row in range(rows):
			var quad: Array[Vector3] = []
			for point in [Vector2(section, row), Vector2(section + 1, row), Vector2(section, row + 1), Vector2(section + 1, row + 1)]:
				var angle: float = -1.35 + point.x / float(sections) * 2.70
				var t: float = point.y / float(rows)
				var radius_x: float = ((0.72 if wide else 0.44) + 0.20 * clampf(abs(angle) / 1.4, 0.0, 1.0)) * factor
				var outward := Vector3(sin(angle) / ARCH_A, 0, cos(angle) / ARCH_B).normalized()
				var lingual := Vector3(sin(angle) * ARCH_A * factor, center_y, (cos(angle) * ARCH_B - ARCH_SHIFT) * factor + shift) + outward * (-radius_x + (-0.20 * factor if wide else 0.0))
				var middle := Vector3(0, peak, lingual.z)
				var p := lingual.lerp(middle, t)
				p.y = lerpf(center_y, peak, t * t * (3.0 - 2.0 * t))
				quad.append(p)
			for index in [0, 2, 1, 1, 2, 3]:
				tool.set_color(PALATE)
				tool.set_normal(Vector3.DOWN)
				tool.add_vertex(quad[index])

## Root anatomy by tooth type: [mesial offset, facial offset (fractions of the crown half-width/depth),
## length (× crown height), radius x, radius z (fractions), mesial tilt, facial tilt].
func root_specs(kind: String, position: int, is_upper: bool, primary: bool) -> Array:
	var specs: Array
	match kind:
		"incisor": specs = [[0.0, 0.0, 1.30 if is_upper and position == 1 else 1.20, 0.70, 0.90, 0.0, 0.0]]
		"canine": specs = [[0.0, 0.0, 1.65, 0.70, 0.90, 0.0, 0.0]]
		"premolar":
			if is_upper and position == 4: specs = [[0.0, 0.42, 1.25, 0.55, 0.42, 0.0, 0.10], [0.0, -0.42, 1.20, 0.55, 0.42, 0.0, -0.10]]
			else: specs = [[0.0, 0.0, 1.35, 0.62, 0.88, 0.0, 0.0]]
		_:
			if is_upper: specs = [[0.45, 0.36, 1.15, 0.38, 0.40, 0.12, 0.08], [-0.45, 0.36, 1.05, 0.36, 0.38, -0.12, 0.08], [0.0, -0.45, 1.25, 0.40, 0.42, 0.0, -0.18]]
			else: specs = [[0.45, 0.0, 1.20, 0.40, 0.80, 0.10, 0.0], [-0.45, 0.0, 1.10, 0.38, 0.74, -0.08, 0.0]]
	if primary:
		for spec in specs:
			spec[2] *= 0.8
			spec[5] *= 1.6
			spec[6] *= 1.6
	return specs

## Point on a root: superellipse section tapering to a rounded apex, divergent and gently curved distally.
func root_point(spec: Array, angle: float, t: float, size: Vector3, sign_y: float, mesial_sign: float) -> Vector3:
	var length: float = spec[2] * size.y
	# Wide at the neck, gently narrowing, rounded apex.
	var taper := 0.10 + 0.90 * pow(1.0 - pow(t, 1.8), 0.7)
	var edge := boundary(angle, size.x * 0.5 * spec[3] * taper, size.z * 0.5 * spec[4] * taper, 2.4)
	var cx: float = spec[0] * size.x * 0.5 * (1.0 - 0.25 * t) + spec[5] * length * pow(t, 1.5) - 0.06 * length * t * t
	var cz: float = spec[1] * size.z * 0.5 * (1.0 - 0.25 * t) + spec[6] * length * pow(t, 1.5)
	return Vector3(edge.x + cx * mesial_sign, -sign_y * (t * length - 0.02), edge.y + cz)

func add_roots(body: Node3D, kind: String, size: Vector3, sign_y: float, is_upper: bool, position: int = 1, primary: bool = false, mesial_sign: float = 1.0) -> void:
	var root_group := Node3D.new()
	root_group.name = "Roots"
	root_group.visible = show_roots
	body.add_child(root_group)
	var tool := SurfaceTool.new()
	tool.begin(Mesh.PRIMITIVE_TRIANGLES)
	var segments := 18
	var rings := 10
	for spec in root_specs(kind, position, is_upper, primary):
		for ring in range(rings):
			for segment in range(segments):
				var a := TAU * segment / segments
				var b := TAU * (segment + 1) / segments
				var t0 := float(ring) / rings
				var t1 := float(ring + 1) / rings
				var corners := [root_point(spec, a, t0, size, sign_y, mesial_sign), root_point(spec, a, t1, size, sign_y, mesial_sign), root_point(spec, b, t0, size, sign_y, mesial_sign),
					root_point(spec, b, t0, size, sign_y, mesial_sign), root_point(spec, a, t1, size, sign_y, mesial_sign), root_point(spec, b, t1, size, sign_y, mesial_sign)]
				var ts := [t0, t1, t0, t0, t1, t1]
				for i in range(6):
					var vertex: Vector3 = corners[i]
					tool.set_color(mottle(CEMENTUM.lerp(APEX, pow(ts[i], 2.0)), vertex))
					# Normal from the root's own (curved) axis at this height.
					var center := root_point(spec, 0.0, ts[i], size, sign_y, mesial_sign).lerp(root_point(spec, PI, ts[i], size, sign_y, mesial_sign), 0.5)
					tool.set_normal((vertex - center).normalized())
					tool.add_vertex(vertex)
	var root := MeshInstance3D.new()
	root.mesh = tool.commit()
	var material := mat(Color.WHITE, 0.55)
	material.vertex_color_use_as_albedo = true
	root.material_override = material
	root_group.add_child(root)

func add_implant(body: Node3D, size: Vector3, sign_y: float) -> void:
	var titanium := mat(Color("9aa8b0"), 0.22)
	titanium.metallic = 0.85
	for index in range(8):
		var screw := MeshInstance3D.new()
		var cylinder := CylinderMesh.new()
		cylinder.top_radius = size.x * 0.20 - index * 0.004
		cylinder.bottom_radius = size.x * 0.17 - index * 0.004
		cylinder.height = 0.07
		cylinder.radial_segments = 20
		screw.mesh = cylinder
		screw.position.y = -sign_y * (0.04 + index * 0.075)
		screw.material_override = titanium
		body.add_child(screw)

func add_absent_marker(body: Node3D, size: Vector3, sign_y: float) -> void:
	# A crossed, softly glowing socket marker keeps recorded loss explicit and selectable.
	var socket := MeshInstance3D.new()
	var disc := CylinderMesh.new()
	disc.top_radius = size.x * 0.42
	disc.bottom_radius = size.x * 0.42
	disc.height = 0.02
	disc.radial_segments = 28
	socket.mesh = disc
	socket.position.y = sign_y * 0.03
	socket.material_override = mat(Color(0.39, 0.45, 0.55, 0.25), 0.7)
	body.add_child(socket)
	for angle in [-0.75, 0.75]:
		var mark := MeshInstance3D.new()
		var box := BoxMesh.new()
		box.size = Vector3(size.x * 0.7, 0.035, 0.06)
		mark.mesh = box
		mark.rotation.y = angle
		mark.position.y = sign_y * 0.06
		mark.material_override = mat(Color("64748b"), 0.7)
		body.add_child(mark)

func face_anchor(body: Node3D, surface: String) -> Vector3:
	var size: Vector3 = body.get_meta("dimensions")
	var sign_y: float = body.get_meta("signY")
	var mesial_sign: float = body.get_meta("mesialSign")
	match surface:
		"F": return Vector3(0, size.y * sign_y * 0.45, size.z * 0.49)
		"L": return Vector3(0, size.y * sign_y * 0.45, -size.z * 0.49)
		"M": return Vector3(size.x * mesial_sign * 0.49, size.y * sign_y * 0.45, 0)
		"D": return Vector3(-size.x * mesial_sign * 0.49, size.y * sign_y * 0.45, 0)
	return Vector3(0, size.y * sign_y * 0.96, 0)

func glow(color: Color) -> StandardMaterial3D:
	var result := mat(color, 0.3)
	result.emission_enabled = true
	result.emission = color
	result.emission_energy_multiplier = 0.35
	return result

## Findings and restorations are painted on the crown surfaces (vertex colours). Open care gets a "+".
func add_state_markers(body: Node3D, state: Dictionary, size: Vector3, sign_y: float) -> void:
	if state.get("treatmentStates", []).is_empty(): return
	var badge := Label3D.new()
	badge.text = "+"
	badge.position = Vector3(0, -sign_y * 0.05, 0.42)
	badge.font_size = 56
	badge.outline_size = 14
	badge.outline_modulate = Color.WHITE
	badge.pixel_size = 0.0045
	badge.modulate = TEAL
	badge.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	body.add_child(badge)

# ==============================================================================================
# Interface
# ==============================================================================================

func box_style(color: Color, radius: int = 24, shadow: int = 0, border: Color = Color(0, 0, 0, 0)) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = color
	style.set_corner_radius_all(radius)
	style.content_margin_left = 20
	style.content_margin_right = 20
	style.content_margin_top = 12
	style.content_margin_bottom = 12
	style.anti_aliasing = true
	if shadow > 0:
		style.shadow_color = Color(0.06, 0.09, 0.16, 0.12)
		style.shadow_size = shadow
		style.shadow_offset = Vector2(0, shadow * 0.35)
	if border.a > 0:
		style.set_border_width_all(2)
		style.border_color = border
	return style

## Labels never wrap unless asked: an autowrapped Label inside a flow/box row has zero minimum
## width and collapses to one character per line.
func label(text: String, size: int, color: Color, bold: bool = false, wrap: bool = false) -> Label:
	var result := Label.new()
	result.text = text
	result.add_theme_font_size_override("font_size", size)
	result.add_theme_color_override("font_color", color)
	if bold:
		result.add_theme_constant_override("outline_size", 1)
		result.add_theme_color_override("font_outline_color", color)
	if wrap:
		result.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		result.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		result.custom_minimum_size.x = 240
	return result

## Pill button. Toggle buttons show a filled teal state when pressed.
func pill(text: String, toggle: bool = false, primary: bool = false, min_width: int = 0) -> Button:
	var button := Button.new()
	button.text = text
	button.toggle_mode = toggle
	button.focus_mode = Control.FOCUS_NONE
	button.custom_minimum_size = Vector2(min_width, 72)
	var base := TEAL if primary else Color(1, 1, 1, 0.0)
	var text_color := Color.WHITE if primary else Color("334155")
	button.add_theme_stylebox_override("normal", box_style(base, 36))
	button.add_theme_stylebox_override("hover", box_style(TEAL.darkened(0.08) if primary else Color(0.06, 0.46, 0.43, 0.08), 36))
	button.add_theme_stylebox_override("pressed", box_style(TEAL.darkened(0.15) if primary else Color.WHITE, 36, 8 if not primary else 0))
	button.add_theme_stylebox_override("hover_pressed", box_style(TEAL.darkened(0.15) if primary else Color.WHITE, 36, 8 if not primary else 0))
	button.add_theme_stylebox_override("disabled", box_style(Color("cbd5df") if primary else Color(1, 1, 1, 0), 36))
	button.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	button.add_theme_color_override("font_color", text_color)
	button.add_theme_color_override("font_hover_color", text_color if primary else TEAL)
	button.add_theme_color_override("font_pressed_color", Color.WHITE if primary else TEAL)
	button.add_theme_color_override("font_hover_pressed_color", Color.WHITE if primary else TEAL)
	button.add_theme_color_override("font_disabled_color", Color("94a3b8"))
	button.add_theme_font_size_override("font_size", 26)
	return button

## Round floating action used in the right-hand rail.
func fab(text: String, toggle: bool = false) -> Button:
	var button := Button.new()
	button.text = text
	button.toggle_mode = toggle
	button.focus_mode = Control.FOCUS_NONE
	button.custom_minimum_size = Vector2(96, 96)
	button.add_theme_stylebox_override("normal", box_style(Color(1, 1, 1, 0.94), 48, 14, HAIRLINE))
	button.add_theme_stylebox_override("hover", box_style(Color.WHITE, 48, 16, TEAL_TINT))
	button.add_theme_stylebox_override("pressed", box_style(TEAL, 48, 14))
	button.add_theme_stylebox_override("hover_pressed", box_style(TEAL, 48, 14))
	button.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	button.add_theme_color_override("font_color", TEAL)
	button.add_theme_color_override("font_hover_color", TEAL)
	button.add_theme_color_override("font_pressed_color", Color.WHITE)
	button.add_theme_color_override("font_hover_pressed_color", Color.WHITE)
	button.add_theme_font_size_override("font_size", 24 if text.length() > 2 else 40)
	return button

func segmented(options: Array, store: Dictionary, on_pick: Callable) -> PanelContainer:
	var holder := PanelContainer.new()
	holder.add_theme_stylebox_override("panel", box_style(Color(0.93, 0.95, 0.96, 0.92), 44, 0))
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 4)
	holder.add_child(row)
	var group := ButtonGroup.new()
	for option in options:
		var button := pill(option, true)
		button.button_group = group
		button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		button.pressed.connect(on_pick.bind(option))
		row.add_child(button)
		store[option] = button
	return holder

func chip(text: String, background: Color, foreground: Color) -> PanelContainer:
	var holder := PanelContainer.new()
	var style := box_style(background, 30)
	style.content_margin_left = 18
	style.content_margin_right = 18
	style.content_margin_top = 6
	style.content_margin_bottom = 6
	holder.add_theme_stylebox_override("panel", style)
	holder.add_child(label(text, 22, foreground))
	return holder

func legend_item(color: Color, text: String, hollow: bool = false) -> HBoxContainer:
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	var dot := Panel.new()
	dot.custom_minimum_size = Vector2(22, 22)
	dot.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var style := box_style(Color(0, 0, 0, 0) if hollow else color, 11, 0, color if hollow else Color(0, 0, 0, 0))
	dot.add_theme_stylebox_override("panel", style)
	row.add_child(dot)
	row.add_child(label(text, 22, MUTED))
	return row

func build_ui() -> void:
	add_child(canvas)
	var safe := MarginContainer.new()
	safe.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	safe.mouse_filter = Control.MOUSE_FILTER_IGNORE
	for side in ["margin_left", "margin_right"]: safe.add_theme_constant_override(side, 28)
	safe.add_theme_constant_override("margin_top", 36)
	safe.add_theme_constant_override("margin_bottom", 28)
	canvas.add_child(safe)

	# --- Top bar --------------------------------------------------------------------------
	var top := VBoxContainer.new()
	top.mouse_filter = Control.MOUSE_FILTER_IGNORE
	top.size_flags_vertical = Control.SIZE_SHRINK_BEGIN
	top.add_theme_constant_override("separation", 16)
	safe.add_child(top)
	var bar := PanelContainer.new()
	bar.add_theme_stylebox_override("panel", box_style(Color(1, 1, 1, 0.94), 36, 18))
	top.add_child(bar)
	var bar_row := HBoxContainer.new()
	bar_row.add_theme_constant_override("separation", 18)
	bar.add_child(bar_row)
	var back := pill("‹  Back")
	back.pressed.connect(go_back)
	bar_row.add_child(back)
	var titles := VBoxContainer.new()
	titles.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	titles.add_theme_constant_override("separation", 0)
	bar_row.add_child(titles)
	titles.add_child(label("3D Dental Chart", 32, INK, true))
	var dentition: String = str(chart.get("dentition", "permanent"))
	var subtitle := dentition.capitalize() + " dentition  ·  Rev " + str(chart.get("chartVersion", 0))
	if read_only: subtitle += "  ·  Read only"
	titles.add_child(label(subtitle, 22, MUTED))
	var reset := pill("Reset")
	reset.pressed.connect(apply_view.bind("Front"))
	bar_row.add_child(reset)

	top.add_child(segmented(VIEWS.keys(), view_buttons, apply_view))
	if dentition == "mixed":
		top.add_child(chip("Mixed dentition  ·  inner row primary, outer row permanent", Color(1, 1, 1, 0.9), MUTED))
	if not input_valid:
		var error := PanelContainer.new()
		error.add_theme_stylebox_override("panel", box_style(Color("fff1f2"), 28, 10, Color("fecdd3")))
		error.add_child(label("This chart version cannot be displayed safely. Return and use the 2D chart.", 26, Color("be123c"), false, true))
		top.add_child(error)

	# --- Right rail -------------------------------------------------------------------------
	var rail := VBoxContainer.new()
	rail.add_theme_constant_override("separation", 14)
	rail.set_anchors_and_offsets_preset(Control.PRESET_CENTER_RIGHT)
	rail.offset_left = -128
	rail.offset_right = -28
	rail.offset_top = -330
	rail.offset_bottom = 170
	canvas.add_child(rail)
	for spec in [["+", false], ["−", false], ["123", true], ["Root", true]]:
		var button := fab(spec[0], spec[1])
		rail.add_child(button)
		if spec[1]: toggle_buttons[spec[0]] = button
		button.pressed.connect(rail_action.bind(spec[0]))
	toggle_buttons["123"].button_pressed = show_labels

	# --- Bottom sheet -----------------------------------------------------------------------
	sheet.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	sheet.grow_vertical = Control.GROW_DIRECTION_BEGIN
	sheet.offset_left = 20
	sheet.offset_right = -20
	sheet.offset_top = -20
	sheet.offset_bottom = -20
	var sheet_style := box_style(SHEET, 44, 26)
	sheet_style.content_margin_left = 32
	sheet_style.content_margin_right = 32
	sheet_style.content_margin_top = 18
	sheet_style.content_margin_bottom = 30
	sheet.add_theme_stylebox_override("panel", sheet_style)
	canvas.add_child(sheet)
	var sheet_body := VBoxContainer.new()
	sheet_body.add_theme_constant_override("separation", 18)
	sheet.add_child(sheet_body)
	var grabber := Panel.new()
	grabber.custom_minimum_size = Vector2(90, 10)
	grabber.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
	grabber.add_theme_stylebox_override("panel", box_style(Color("cbd5df"), 5))
	sheet_body.add_child(grabber)
	sheet_body.add_child(sheet_idle)
	sheet_body.add_child(sheet_detail)

	# Idle: arch filter, guidance and legend.
	sheet_idle.add_theme_constant_override("separation", 18)
	sheet_idle.add_child(segmented(["Both", "Upper", "Lower"], arch_buttons, set_arch))
	sheet_idle.add_child(label("View only · tap a tooth to review its record." if read_only else "Tap a tooth to review it and record treatment.", 25, INK, false, true))
	sheet_idle.add_child(label("Drag to rotate  ·  pinch to zoom  ·  two fingers to pan  ·  double-tap to reset", 21, MUTED, false, true))
	var legend := HFlowContainer.new()
	legend.add_theme_constant_override("h_separation", 26)
	legend.add_theme_constant_override("v_separation", 12)
	legend.add_child(legend_item(ENAMEL.darkened(0.12), "Assessed"))
	legend.add_child(legend_item(Color("94a3b8"), "Unassessed", true))
	legend.add_child(legend_item(AMBER, "Finding"))
	legend.add_child(legend_item(BLUE, "Restoration"))
	legend.add_child(legend_item(Color("60a5fa"), "Treated"))
	legend.add_child(legend_item(TEAL, "Planned care (+)"))
	legend.add_child(legend_item(Color("64748b"), "Recorded loss (×)"))
	sheet_idle.add_child(legend)

	# Detail: selected tooth summary, surface pad and the primary action.
	sheet_detail.visible = false
	sheet_detail.add_theme_constant_override("separation", 18)
	var header := HBoxContainer.new()
	header.add_theme_constant_override("separation", 20)
	sheet_detail.add_child(header)
	var badge := PanelContainer.new()
	badge.custom_minimum_size = Vector2(104, 104)
	badge.add_theme_stylebox_override("panel", box_style(TEAL, 30))
	badge_label.add_theme_font_size_override("font_size", 34)
	badge_label.add_theme_color_override("font_color", Color.WHITE)
	badge_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	badge_label.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	badge.add_child(badge_label)
	header.add_child(badge)
	var names := VBoxContainer.new()
	names.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	names.alignment = BoxContainer.ALIGNMENT_CENTER
	header.add_child(names)
	name_label.add_theme_font_size_override("font_size", 30)
	name_label.add_theme_color_override("font_color", INK)
	name_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	name_label.custom_minimum_size.x = 260
	names.add_child(name_label)
	status_label.add_theme_font_size_override("font_size", 23)
	status_label.add_theme_color_override("font_color", MUTED)
	status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	status_label.custom_minimum_size.x = 260
	names.add_child(status_label)
	var close := fab("×")
	close.custom_minimum_size = Vector2(80, 80)
	close.pressed.connect(clear_selection)
	header.add_child(close)
	chip_row.add_theme_constant_override("h_separation", 10)
	chip_row.add_theme_constant_override("v_separation", 10)
	sheet_detail.add_child(chip_row)

	var pad_row := HBoxContainer.new()
	pad_row.add_theme_constant_override("separation", 24)
	sheet_detail.add_child(pad_row)
	surface_pad.columns = 3
	surface_pad.add_theme_constant_override("h_separation", 8)
	surface_pad.add_theme_constant_override("v_separation", 8)
	pad_row.add_child(surface_pad)
	var pad_text := VBoxContainer.new()
	pad_text.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	pad_text.alignment = BoxContainer.ALIGNMENT_CENTER
	pad_row.add_child(pad_text)
	pad_text.add_child(label("Surface", 24, INK, true))
	surface_hint.add_theme_font_size_override("font_size", 22)
	surface_hint.add_theme_color_override("font_color", MUTED)
	surface_hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	surface_hint.custom_minimum_size.x = 260
	pad_text.add_child(surface_hint)
	var whole := pill("Whole tooth", true)
	whole.pressed.connect(pick_surface.bind(""))
	surface_buttons[""] = whole
	pad_text.add_child(whole)

	assess_button = pill("Record treatment", false, true)
	assess_button.custom_minimum_size = Vector2(0, 96)
	assess_button.add_theme_font_size_override("font_size", 30)
	assess_button.pressed.connect(edit_selected)
	sheet_detail.add_child(assess_button)

	view_buttons["Front"].button_pressed = true
	arch_buttons["Both"].button_pressed = true

func go_back() -> void:
	if bridge: bridge.closeChart()

func rail_action(caption: String) -> void:
	match caption:
		"+": distance = clamp(distance / 1.18, 6.0, 26.0)
		"−": distance = clamp(distance * 1.18, 6.0, 26.0)
		"123":
			show_labels = toggle_buttons["123"].button_pressed
			for body in teeth.values(): body.get_node("SlotLabel").visible = show_labels or body.get_meta("toothKey") == selected
		"Root":
			show_roots = toggle_buttons["Root"].button_pressed
			for body in teeth.values():
				if body.has_node("Roots"): body.get_node("Roots").visible = show_roots
			# X-ray look: soften the gingiva so roots are visible inside the bone line.
			for gum_material in gum_materials:
				gum_material.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA if show_roots else BaseMaterial3D.TRANSPARENCY_DISABLED
				gum_material.albedo_color = Color(1, 1, 1, 0.32 if show_roots else 1.0)

func apply_view(view: String) -> void:
	if not VIEWS.has(view): return
	var preset: Array = VIEWS[view]
	current_view = view
	pitch = preset[0]
	yaw = preset[1]
	distance = preset[2]
	target = Vector3.ZERO
	yaw_velocity = 0.0
	set_arch(preset[3])
	if view_buttons.has(view): view_buttons[view].button_pressed = true

func set_arch(filter: String) -> void:
	arch_filter = filter
	set_arch_visible(upper, filter != "Lower")
	set_arch_visible(lower, filter != "Upper")
	if arch_buttons.has(filter): arch_buttons[filter].button_pressed = true

func set_arch_visible(arch: Node3D, visible_now: bool) -> void:
	arch.visible = visible_now
	# Visibility alone does not disable physics picking; keep hidden arches out of rays.
	for child in arch.get_children():
		if child is StaticBody3D: child.collision_layer = 1 if visible_now else 0
	if not visible_now and teeth.has(selected) and teeth[selected].get_parent() == arch:
		clear_selection()

func update_camera() -> void:
	rig.rotation = Vector3(cur_pitch, cur_yaw, 0)
	camera.position = cur_target + Vector3(0, cur_distance * 0.30, cur_distance)
	camera.look_at(cur_target)

func _process(delta: float) -> void:
	# Inertial orbit after a fling, then critically damped easing toward the targets.
	if fingers.is_empty() and abs(yaw_velocity) > 0.001:
		yaw += yaw_velocity * delta
		yaw_velocity *= exp(-4.0 * delta)
	var ease := 1.0 - exp(-10.0 * delta)
	cur_yaw = lerpf(cur_yaw, yaw, ease)
	cur_pitch = lerpf(cur_pitch, pitch, ease)
	cur_distance = lerpf(cur_distance, distance, ease)
	cur_target = cur_target.lerp(target, ease)
	update_camera()
	if teeth.has(selected):
		pulse += delta
		var body: Node3D = teeth[selected]
		if body.has_meta("crownMaterial"):
			var material: StandardMaterial3D = body.get_meta("crownMaterial")
			material.emission_energy_multiplier = 0.22 + 0.12 * sin(pulse * 3.2)
		if selected_halo:
			selected_halo.rotation.y += delta * 0.8

# ==============================================================================================
# Selection
# ==============================================================================================

func clear_selection() -> void:
	if teeth.has(selected):
		var body: Node3D = teeth[selected]
		if body.has_meta("crownMaterial"):
			var material: StandardMaterial3D = body.get_meta("crownMaterial")
			material.emission_enabled = false
		body.get_node("SlotLabel").visible = show_labels
		body.get_node("SlotLabel").modulate = Color("334155")
	if selected_halo:
		selected_halo.queue_free()
		selected_halo = null
	selected = ""
	selected_surface = ""
	target = Vector3.ZERO
	sheet_detail.visible = false
	sheet_idle.visible = true
	animate_sheet()

func select_tooth(body: Node3D) -> void:
	var key: String = body.get_meta("toothKey")
	if key == selected: return
	clear_selection()
	selected = key
	selected_surface = ""
	var state: Dictionary = chart.get("teeth", {}).get(selected, {})
	if body.has_meta("crownMaterial"):
		var material: StandardMaterial3D = body.get_meta("crownMaterial")
		material.emission_enabled = true
		material.emission = TEAL_LIGHT
		material.emission_energy_multiplier = 0.25
	var slot_label: Label3D = body.get_node("SlotLabel")
	slot_label.visible = true
	slot_label.modulate = TEAL
	selected_halo = MeshInstance3D.new()
	var ring := TorusMesh.new()
	ring.inner_radius = 0.44
	ring.outer_radius = 0.49
	ring.rings = 32
	ring.ring_segments = 12
	selected_halo.mesh = ring
	var size: Vector3 = body.get_meta("dimensions")
	selected_halo.scale = Vector3(size.x * 1.25, 0.5, size.z * 1.25)
	selected_halo.position.y = body.get_meta("signY") * 0.02
	selected_halo.material_override = glow(TEAL_LIGHT)
	body.add_child(selected_halo)
	# Gently bring the tooth toward the centre without losing arch context.
	target = body.global_position * 0.45
	distance = min(distance, 10.5)
	badge_label.text = notation_label(selected)
	name_label.text = tooth_name(selected) + ("" if notation == "fdi" else "  (FDI " + selected + ")")
	status_label.text = str(state.get("assessment", "not_assessed")).replace("_", " ").capitalize() + "  ·  " + str(state.get("presence", "unknown")).replace("_", " ")
	if state.get("replacement", "none") not in ["none", ""]:
		status_label.text += "  ·  " + str(state.get("replacement")).replace("_", " ")
	build_surface_pad(state)
	refresh_details(state)
	sheet_idle.visible = false
	sheet_detail.visible = true
	animate_sheet()

func animate_sheet() -> void:
	# Fade only: the sheet resizes with its content, so its position is owned by layout.
	sheet.modulate.a = 0.0
	create_tween().tween_property(sheet, "modulate:a", 1.0, 0.22).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)

func build_surface_pad(state: Dictionary) -> void:
	for child in surface_pad.get_children(): child.queue_free()
	for key in surface_buttons.keys():
		if key != "": surface_buttons.erase(key)
	var anterior := int(selected[1]) <= 3
	var centre := "I" if anterior else "O"
	# Seen from the front, a tooth on the patient's right has its mesial side on the viewer's right.
	var patient_right := float(teeth[selected].get_meta("mesialSign")) > 0.0
	var layout := ["", "F", "", "D" if patient_right else "M", centre, "M" if patient_right else "D", "", "L", ""]
	var group := ButtonGroup.new()
	surface_buttons[""].button_group = group
	surface_buttons[""].button_pressed = true
	for cell in layout:
		if cell == "":
			var spacer := Control.new()
			spacer.custom_minimum_size = Vector2(84, 84)
			surface_pad.add_child(spacer)
			continue
		var button := pill(cell, true)
		button.custom_minimum_size = Vector2(84, 84)
		button.add_theme_stylebox_override("normal", box_style(surface_tint(state, cell), 20, 0, HAIRLINE))
		button.add_theme_stylebox_override("pressed", box_style(TEAL, 20, 6))
		button.add_theme_stylebox_override("hover_pressed", box_style(TEAL, 20, 6))
		button.add_theme_color_override("font_pressed_color", Color.WHITE)
		button.add_theme_color_override("font_hover_pressed_color", Color.WHITE)
		button.button_group = group
		button.pressed.connect(pick_surface.bind(cell))
		surface_buttons[cell] = button
		surface_pad.add_child(button)

func surface_tint(state: Dictionary, surface: String) -> Color:
	var observation: Dictionary = state.get("surfaces", {}).get(surface, {})
	if not observation.get("findings", []).is_empty(): return Color("fef3c7")
	if not observation.get("restorations", []).is_empty(): return Color("dbeafe")
	if observation.get("assessment", "") == "assessed": return Color("f0fdfa")
	return Color.WHITE

func pick_surface(surface: String) -> void:
	selected_surface = surface
	if surface_buttons.has(surface): surface_buttons[surface].button_pressed = true
	refresh_details(chart.get("teeth", {}).get(selected, {}))

func refresh_details(state: Dictionary) -> void:
	for child in chip_row.get_children(): child.queue_free()
	var facts: Dictionary = state if selected_surface == "" else state.get("surfaces", {}).get(selected_surface, {})
	var findings: Array = facts.get("findings", [])
	var restorations: Array = facts.get("restorations", [])
	for code in findings: chip_row.add_child(chip("Finding · " + str(code).replace("_", " "), Color("fef3c7"), Color("92400e")))
	for code in restorations: chip_row.add_child(chip("Restoration · " + str(code).replace("_", " "), Color("dbeafe"), Color("1e40af")))
	for code in state.get("treatmentStates", []): chip_row.add_child(chip("Care · " + str(code).replace("_", " "), TEAL_TINT, TEAL))
	if chip_row.get_child_count() == 0:
		chip_row.add_child(chip("No coded findings recorded" + ("" if selected_surface == "" else " on " + surface_name(selected_surface)), Color("eef3f6"), MUTED))
	if selected_surface == "":
		surface_hint.text = "Whole tooth selected. Tap a surface on the pad to assess it individually."
	else:
		surface_hint.text = surface_name(selected_surface) + " surface · " + str(facts.get("assessment", "not_assessed")).replace("_", " ")
	assess_button.text = "Record treatment  ·  " + notation_label(selected) + ("" if selected_surface == "" else "  ·  " + surface_name(selected_surface))
	assess_button.disabled = read_only
	if read_only: assess_button.text = "View only"

func surface_name(surface: String) -> String:
	var anterior := selected != "" and int(selected[1]) <= 3
	var upper_arch := selected != "" and selected[0] in ["1", "2", "5", "6"]
	match surface:
		"M": return "Mesial"
		"D": return "Distal"
		"F": return "Labial" if anterior else "Buccal"
		"L": return "Palatal" if upper_arch else "Lingual"
		"I": return "Incisal"
		"O": return "Occlusal"
	return surface

## Display numbering (stored keys stay FDI). Palmer: 1-8 / A-E per quadrant; Universal: 1-32 / A-T.
func notation_short(key: String) -> String:
	var quadrant := int(key[0])
	var position := int(key[1])
	match notation:
		"fdi": return key
		"universal":
			var codes := {1: 9 - position, 2: 8 + position, 3: 25 - position, 4: 24 + position}
			if codes.has(quadrant): return str(codes[quadrant])
			var letters := "ABCDEFGHIJKLMNOPQRST"
			var index := {5: 5 - position, 6: 4 + position, 7: 15 - position, 8: 14 + position}
			return letters[index[quadrant]]
	return str(position) if quadrant <= 4 else "ABCDE"[position - 1]

func notation_label(key: String) -> String:
	match notation:
		"fdi": return key
		"universal": return "#" + notation_short(key)
	var prefix := {1: "UR", 2: "UL", 3: "LL", 4: "LR", 5: "UR", 6: "UL", 7: "LL", 8: "LR"}
	return prefix[int(key[0])] + notation_short(key)

func tooth_name(key: String) -> String:
	var quadrant := int(key[0])
	var position := int(key[1])
	var primary := quadrant >= 5
	var arch := "Upper" if quadrant in [1, 2, 5, 6] else "Lower"
	var side := "right" if quadrant in [1, 4, 5, 8] else "left"
	var names := ["central incisor", "lateral incisor", "canine", "first molar", "second molar"] if primary else ["central incisor", "lateral incisor", "canine", "first premolar", "second premolar", "first molar", "second molar", "third molar"]
	if position < 1 or position > names.size(): return "Tooth " + key
	return arch + " " + side + " " + ("primary " if primary else "") + names[position - 1]

func edit_selected() -> void:
	if not input_valid or read_only or selected == "" or not bridge: return
	if selected_surface != "": bridge.selectSurface(selected, selected_surface)
	else: bridge.selectTooth(selected)

# ==============================================================================================
# Input
# ==============================================================================================

func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			fingers[event.index] = event.position
			touch_origin = event.position
			yaw_velocity = 0.0
			if fingers.size() == 1: moved = false
		else:
			fingers.erase(event.index)
			if not moved and event.position.distance_to(touch_origin) < 14: register_tap(event.position)
	elif event is InputEventScreenDrag:
		moved = moved or event.position.distance_to(touch_origin) > 8
		if fingers.size() >= 2:
			var before: Array = fingers.values()
			var old_distance: float = before[0].distance_to(before[1])
			var old_center: Vector2 = (before[0] + before[1]) * 0.5
			fingers[event.index] = event.position
			var after: Array = fingers.values()
			var new_distance: float = after[0].distance_to(after[1])
			var center: Vector2 = (after[0] + after[1]) * 0.5
			if new_distance > 8 and old_distance > 8: distance = clamp(distance * old_distance / new_distance, 6.0, 26.0)
			pan(center - old_center)
		else:
			fingers[event.index] = event.position
			orbit(event.relative)
	elif event is InputEventMagnifyGesture:
		distance = clamp(distance / event.factor, 6.0, 26.0)
	elif event is InputEventMouseMotion and Input.is_mouse_button_pressed(MOUSE_BUTTON_LEFT):
		moved = moved or event.relative.length() > 2
		orbit(event.relative)
	elif event is InputEventMouseMotion and Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT):
		pan(event.relative)
	elif event is InputEventMouseButton:
		if event.button_index == MOUSE_BUTTON_LEFT:
			if event.pressed:
				moved = false
				yaw_velocity = 0.0
			elif not moved: register_tap(event.position)
		elif event.pressed and event.button_index == MOUSE_BUTTON_WHEEL_UP: rail_action("+")
		elif event.pressed and event.button_index == MOUSE_BUTTON_WHEEL_DOWN: rail_action("−")

func orbit(relative: Vector2) -> void:
	yaw += relative.x * 0.006
	pitch = clamp(pitch + relative.y * 0.005, -1.35, 1.35)
	yaw_velocity = relative.x * 0.006 * 60.0
	if view_buttons.has(current_view) and (abs(relative.x) > 2 or abs(relative.y) > 2):
		view_buttons[current_view].button_pressed = false

func register_tap(at: Vector2) -> void:
	# Double tap on empty space resets the view; single taps pick teeth.
	var now := Time.get_ticks_msec()
	if now - last_tap_ms < 300 and selected == "":
		apply_view("Front")
	last_tap_ms = now
	pending_pick = at

func _input(event: InputEvent) -> void:
	# A touch may begin on the model and end over a GUI button, whose release is
	# consumed before _unhandled_input. Always release its tracked finger.
	if event is InputEventScreenTouch and not event.pressed: fingers.erase(event.index)

func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST:
		if selected != "": clear_selection()
		else: go_back()
		return
	if what == NOTIFICATION_APPLICATION_FOCUS_OUT:
		fingers.clear()
		moved = true

func pan(relative: Vector2) -> void:
	target += camera.global_basis.x * -relative.x * distance * 0.0009
	target += camera.global_basis.y * relative.y * distance * 0.0009
	target.x = clamp(target.x, -4.0, 4.0)
	target.y = clamp(target.y, -3.0, 3.0)

func _physics_process(_delta: float) -> void:
	if pending_pick.x >= 0:
		pick(pending_pick)
		pending_pick = Vector2(-1, -1)

func pick(screen: Vector2) -> void:
	var origin := camera.project_ray_origin(screen)
	var query := PhysicsRayQueryParameters3D.create(origin, origin + camera.project_ray_normal(screen) * 100.0)
	var result := get_world_3d().direct_space_state.intersect_ray(query)
	if result.is_empty() or not result.collider.has_meta("toothKey"): return
	select_tooth(result.collider)
