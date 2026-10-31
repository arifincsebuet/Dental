extends Node3D
## Educational procedural dentition. Clinical records remain in the native host.
## Each anatomical slot has a stable FDI body, including recorded missing slots.

const ENAMEL := Color("fff8e8")
const UNKNOWN := Color("d9e1e5")
const TEAL := Color("0f766e")
const BLUE := Color("2563eb")
const AMBER := Color("e89a38")
const LOST := ["missing", "extracted", "exfoliated", "congenitally_absent"]
const SURFACES := ["M", "D", "F", "L", "I", "O"]
var bridge
var chart: Dictionary = {}
var teeth: Dictionary = {}
var rig := Node3D.new()
var camera := Camera3D.new()
var upper := Node3D.new()
var lower := Node3D.new()
var target := Vector3.ZERO
var yaw := 0.0
var pitch := -0.25
var distance := 13.0
var selected := ""
var selected_surface := ""
var pending_pick := Vector2(-1, -1)
var fingers: Dictionary = {}
var touch_origin := Vector2.ZERO
var moved := false
var selected_label := Label.new()
var details_label := Label.new()
var error_label := Label.new()
var assess_button := Button.new()
var surface_row := HFlowContainer.new()
var selected_halo: MeshInstance3D
var show_labels := true
var show_roots := false
var input_valid := true
var read_only := false

func mat(color: Color, roughness: float = 0.28) -> StandardMaterial3D:
	var result := StandardMaterial3D.new()
	result.albedo_color = color
	result.roughness = roughness
	result.metallic_specular = 0.52
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
		else:
			input_valid = false
	add_child(rig)
	upper.name = "UpperArch"
	lower.name = "LowerArch"
	rig.add_child(upper)
	rig.add_child(lower)
	add_child(camera)
	camera.fov = 43
	camera.near = 0.05
	var environment := WorldEnvironment.new()
	environment.environment = Environment.new()
	environment.environment.background_mode = Environment.BG_COLOR
	environment.environment.background_color = Color("f0fdfa")
	environment.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.environment.ambient_light_color = Color("e8f0f4")
	environment.environment.ambient_light_energy = 0.67
	add_child(environment)
	for position in [Vector3(-5, 8, 6), Vector3(6, 2, -5), Vector3(0, -5, 5)]:
		var light := DirectionalLight3D.new()
		add_child(light)
		light.position = position
		light.look_at(Vector3.ZERO)
		light.light_energy = 0.7
		light.light_color = Color("fff7ec") if position.y > 5 else Color("e8f6ff")
	if input_valid:
		build_inventory()
	build_ui()
	update_camera()
	set_process_unhandled_input(input_valid)

func build_inventory() -> void:
	var dentition: String = chart.get("dentition", "permanent")
	if dentition not in ["primary", "permanent", "mixed"]:
		input_valid = false
		return
	if dentition != "primary":
		build_jaw(1, false, upper, 1.0, 0.0)
		build_jaw(4, false, lower, 0.96, 0.0)
	if dentition != "permanent":
		# Mixed dentition has two labeled inventories. Primary slots occupy an inner
		# diagrammatic row rather than replacing or silently overlapping successors.
		var factor := 0.72 if dentition == "mixed" else 0.92
		var shift := 0.2 if dentition == "mixed" else 0.0
		build_jaw(5, true, upper, factor, shift)
		build_jaw(8, true, lower, factor * 0.96, shift)

func build_jaw(first_quadrant: int, primary: bool, parent: Node3D, factor: float, shift: float) -> void:
	var max_position := 5 if primary else 8
	var is_upper := first_quadrant in [1, 5]
	var sign_y := -1.0 if is_upper else 1.0
	var collar_y := 1.55 if is_upper else -1.55
	var spatial_factor := factor * (0.72 if primary else 1.0)
	parent.add_child(gum_band(collar_y, spatial_factor, shift))
	for side in range(2):
		var quadrant := first_quadrant + side if is_upper else first_quadrant - side
		for position in range(1, max_position + 1):
			var key := str(quadrant) + str(position)
			var state: Dictionary = chart.get("teeth", {}).get(key, {})
			var theta := (float(position) - 0.45) / float(max_position) * 1.68
			var side_sign := -1.0 if side == 0 else 1.0
			var body := StaticBody3D.new()
			body.name = "Tooth_" + key
			body.position = Vector3(sin(theta) * 3.5 * side_sign * spatial_factor, collar_y, (-cos(theta) * 4.0 + 1.1) * spatial_factor + shift)
			body.rotation.y = atan2(sin(theta) * side_sign, -cos(theta))
			body.set_meta("toothKey", key)
			body.set_meta("signY", sign_y)
			body.set_meta("primary", primary)
			parent.add_child(body)
			var kind := "incisor" if position <= 2 else "canine" if position == 3 else "molar" if primary or position >= 6 else "premolar"
			var dimensions := Vector3(0.72, 0.96, 0.39)
			if position == 2: dimensions.x = 0.62
			if kind == "canine": dimensions = Vector3(0.64, 1.11, 0.61)
			if kind == "premolar": dimensions = Vector3(0.73, 0.86, 0.82)
			if kind == "molar": dimensions = Vector3(0.96, 0.82, 1.0)
			dimensions *= factor
			body.set_meta("dimensions", dimensions)
			var presence: String = state.get("presence", "unknown")
			var replacement: String = state.get("replacement", "none")
			var absent := presence in LOST
			if not absent or replacement not in ["none", ""]:
				var color := ENAMEL if state.get("assessment", "not_assessed") == "assessed" else UNKNOWN
				if presence == "unerupted": color = Color(0.76, 0.84, 0.88, 0.42)
				var crown := MeshInstance3D.new()
				crown.name = "Crown"
				crown.mesh = crown_mesh(kind, dimensions, sign_y)
				crown.material_override = mat(color)
				body.add_child(crown)
				if replacement == "implant": add_implant(body, dimensions, sign_y)
				else: add_roots(body, kind, dimensions, sign_y, is_upper)
				add_state_markers(body, state, dimensions, sign_y)
			else:
				add_absent_marker(body, dimensions, sign_y)
			var collision := CollisionShape3D.new()
			var box := BoxShape3D.new()
			box.size = dimensions * Vector3(1.05, 1.0, 1.05)
			collision.position.y = sign_y * dimensions.y * 0.45
			collision.shape = box
			body.add_child(collision)
			var tooth_label := Label3D.new()
			tooth_label.name = "SlotLabel"
			tooth_label.text = key
			tooth_label.font_size = 38
			tooth_label.pixel_size = 0.006
			tooth_label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
			tooth_label.modulate = TEAL
			tooth_label.outline_modulate = Color.WHITE
			tooth_label.position = Vector3(0, sign_y * (dimensions.y + 0.20), dimensions.z * 0.55)
			body.add_child(tooth_label)
			teeth[key] = body

func crown_mesh(kind: String, size: Vector3, sign_y: float) -> ArrayMesh:
	var tool := SurfaceTool.new()
	tool.begin(Mesh.PRIMITIVE_TRIANGLES)
	var segments := 40
	var levels := 8
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
			for vertex in [p0, p2, p1, p1, p2, p3]:
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
			for index in [0, 1, 2, 2, 1, 3]:
				var vertex := points[index]
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
	return 0.48 if kind == "incisor" else 0.83 if kind == "canine" else 0.90

func crown_side(angle: float, level: float, half_x: float, half_z: float, height: float, exponent: float, kind: String, sign_y: float) -> Vector3:
	var edge := boundary(angle, half_x, half_z, exponent)
	var belly := 0.74 + sin(level * PI * 0.85) * 0.27
	var width: float = lerpf(belly, tip_width(kind), pow(level, 4.0))
	var depth: float = lerpf(belly, tip_depth(kind), pow(level, 4.0))
	var x := edge.x * width
	var z := edge.y * depth
	var top := top_height(kind, edge.x * tip_width(kind) / half_x, edge.y * tip_depth(kind) / half_z)
	return Vector3(x, level * top * height * sign_y, z)

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

func gum_band(height: float, factor: float, shift: float) -> MeshInstance3D:
	var tool := SurfaceTool.new()
	tool.begin(Mesh.PRIMITIVE_TRIANGLES)
	for section in range(64):
		for ring in range(12):
			var vertices: Array[Vector3] = []
			var normals: Array[Vector3] = []
			for point in [Vector2(section, ring), Vector2(section + 1, ring), Vector2(section, ring + 1), Vector2(section + 1, ring + 1)]:
				var angle: float = -1.72 + point.x / 64.0 * 3.44
				var cross_angle: float = point.y / 12.0 * TAU
				var outward := Vector3(sin(angle), 0, -cos(angle))
				var center := Vector3(sin(angle) * 3.5 * factor, height + (0.12 if height > 0 else -0.12), (-cos(angle) * 4.0 + 1.1) * factor + shift)
				vertices.append(center + outward * cos(cross_angle) * 0.42 * factor + Vector3.UP * sin(cross_angle) * 0.24)
				normals.append((outward * cos(cross_angle) + Vector3.UP * sin(cross_angle)).normalized())
			for index in [0, 2, 1, 1, 2, 3]:
				tool.set_normal(normals[index])
				tool.add_vertex(vertices[index])
	var mesh := MeshInstance3D.new()
	mesh.mesh = tool.commit()
	mesh.material_override = mat(Color("dca3a1"), 0.55)
	return mesh

func add_roots(body: Node3D, kind: String, size: Vector3, sign_y: float, is_upper: bool) -> void:
	var root_group := Node3D.new()
	root_group.name = "Roots"
	root_group.visible = show_roots
	body.add_child(root_group)
	var count := 3 if kind == "molar" and is_upper else 2 if kind == "molar" or kind == "premolar" else 1
	for index in range(count):
		var root := MeshInstance3D.new()
		var cylinder := CylinderMesh.new()
		cylinder.top_radius = 0.045 if sign_y > 0 else 0.12
		cylinder.bottom_radius = 0.12 if sign_y > 0 else 0.045
		cylinder.height = size.y * 1.05
		cylinder.radial_segments = 16
		root.mesh = cylinder
		root.position = Vector3((float(index) - (count - 1) * 0.5) * size.x * 0.35, -sign_y * size.y * 0.51, 0)
		root.rotation.z = (float(index) - (count - 1) * 0.5) * 0.15 * sign_y
		root.material_override = mat(Color("e8d3ad"), 0.5)
		root_group.add_child(root)

func add_implant(body: Node3D, size: Vector3, sign_y: float) -> void:
	for index in range(7):
		var screw := MeshInstance3D.new()
		var cylinder := CylinderMesh.new()
		cylinder.top_radius = size.x * 0.20
		cylinder.bottom_radius = size.x * 0.18
		cylinder.height = 0.07
		cylinder.radial_segments = 20
		screw.mesh = cylinder
		screw.position.y = -sign_y * (0.04 + index * 0.065)
		var titanium := mat(Color("98a6ad"), 0.24)
		titanium.metallic = 0.8
		screw.material_override = titanium
		body.add_child(screw)

func add_absent_marker(body: Node3D, size: Vector3, sign_y: float) -> void:
	for angle in [-0.65, 0.65]:
		var mark := MeshInstance3D.new()
		var box := BoxMesh.new()
		box.size = Vector3(size.x * 0.65, 0.035, 0.06)
		mark.mesh = box
		mark.rotation.y = angle
		mark.position.y = sign_y * 0.06
		mark.material_override = mat(Color("64748b"), 0.7)
		body.add_child(mark)

func face_anchor(body: Node3D, surface: String) -> Vector3:
	var size: Vector3 = body.get_meta("dimensions")
	var sign_y: float = body.get_meta("signY")
	var quadrant := int(str(body.get_meta("toothKey"))[0])
	var mesial_sign := -1.0 if quadrant in [1, 4, 5, 8] else 1.0
	match surface:
		"F": return Vector3(0, size.y * sign_y * 0.45, size.z * 0.49)
		"L": return Vector3(0, size.y * sign_y * 0.45, -size.z * 0.49)
		"M": return Vector3(size.x * mesial_sign * 0.49, size.y * sign_y * 0.45, 0)
		"D": return Vector3(-size.x * mesial_sign * 0.49, size.y * sign_y * 0.45, 0)
	return Vector3(0, size.y * sign_y * 0.96, 0)

func add_state_markers(body: Node3D, state: Dictionary, size: Vector3, sign_y: float) -> void:
	var surfaces: Dictionary = state.get("surfaces", {})
	for surface in surfaces:
		if surface not in SURFACES: continue
		var observation: Dictionary = surfaces[surface]
		var findings: Array = observation.get("findings", [])
		var restorations: Array = observation.get("restorations", [])
		if findings.is_empty() and restorations.is_empty(): continue
		var anchor := face_anchor(body, surface)
		var normal := anchor - Vector3(0, size.y * sign_y * 0.45, 0)
		if surface in ["I", "O"]: normal = Vector3.UP * sign_y
		normal = normal.normalized()
		for index in range(2):
			if (index == 0 and findings.is_empty()) or (index == 1 and restorations.is_empty()): continue
			var marker := MeshInstance3D.new()
			var sphere := SphereMesh.new()
			sphere.radius = 0.095
			sphere.height = 0.19
			sphere.radial_segments = 16
			marker.mesh = sphere
			marker.scale = Vector3(1, 0.14, 1)
			marker.quaternion = Quaternion(Vector3.UP, normal)
			marker.position = anchor + normal * 0.025 + Vector3(0.08 if index == 1 else -0.08, 0, 0)
			marker.material_override = mat(AMBER if index == 0 else BLUE, 0.3)
			body.add_child(marker)
	if not state.get("findings", []).is_empty() or not state.get("treatmentStates", []).is_empty():
		var badge := Label3D.new()
		badge.text = "!" if not state.get("findings", []).is_empty() else "+"
		badge.position = Vector3(0, sign_y * size.y * 0.45, size.z * 0.7)
		badge.font_size = 48
		badge.pixel_size = 0.005
		badge.modulate = AMBER if badge.text == "!" else TEAL
		badge.billboard = BaseMaterial3D.BILLBOARD_ENABLED
		body.add_child(badge)

func build_ui() -> void:
	var canvas := CanvasLayer.new()
	add_child(canvas)
	var header := VBoxContainer.new()
	header.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	header.offset_left = 24
	header.offset_right = -24
	header.offset_top = 28
	canvas.add_child(header)
	var title := Label.new()
	title.text = "DENTAL CARE  /  3D CHART  ·  REV " + str(chart.get("chartVersion", 0))
	title.add_theme_font_size_override("font_size", 29)
	title.add_theme_color_override("font_color", TEAL)
	header.add_child(title)
	var hint := Label.new()
	hint.text = "Drag to orbit · two fingers to pan / pinch to zoom · tap a tooth"
	hint.add_theme_font_size_override("font_size", 19)
	hint.add_theme_color_override("font_color", Color("475569"))
	hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	header.add_child(hint)
	var controls := HFlowContainer.new()
	header.add_child(controls)
	for caption in ["Back", "Reset", "Focus", "Upper", "Lower", "Labels", "Roots", "−", "+"]:
		var button := ui_button(caption)
		button.pressed.connect(action.bind(caption))
		controls.add_child(button)
	if chart.get("dentition", "permanent") == "mixed":
		var mixed_note := Label.new()
		mixed_note.text = "MIXED DENTITION · inner row = primary; outer row = permanent"
		mixed_note.add_theme_color_override("font_color", Color("475569"))
		mixed_note.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		header.add_child(mixed_note)
	var panel := PanelContainer.new()
	panel.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	panel.offset_left = 24
	panel.offset_right = -24
	panel.offset_top = -330
	panel.offset_bottom = -24
	panel.add_theme_stylebox_override("panel", panel_style(Color(1, 1, 1, 0.96)))
	canvas.add_child(panel)
	var content := VBoxContainer.new()
	content.add_theme_constant_override("separation", 10)
	panel.add_child(content)
	selected_label.text = "Select a tooth"
	if read_only: selected_label.text = "Historical chart · read only"
	selected_label.add_theme_font_size_override("font_size", 26)
	selected_label.add_theme_color_override("font_color", TEAL)
	content.add_child(selected_label)
	details_label.text = "Gray enamel: unassessed · Amber: finding · Blue: restoration\nA + badge indicates open planned care. Recorded loss uses a crossed slot."
	details_label.add_theme_font_size_override("font_size", 20)
	details_label.add_theme_color_override("font_color", Color("334155"))
	details_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	content.add_child(details_label)
	content.add_child(surface_row)
	assess_button = ui_button("Assess selected tooth")
	assess_button.disabled = true
	assess_button.pressed.connect(edit_selected)
	content.add_child(assess_button)
	if not input_valid:
		error_label.text = "This chart version cannot be displayed safely. Return to the native 2D chart."
		error_label.add_theme_color_override("font_color", Color("b91c1c"))
		error_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		header.add_child(error_label)

func panel_style(color: Color) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = color
	style.set_corner_radius_all(18)
	style.content_margin_left = 18
	style.content_margin_right = 18
	style.content_margin_top = 14
	style.content_margin_bottom = 14
	return style

func ui_button(text: String) -> Button:
	var button := Button.new()
	button.text = text
	button.custom_minimum_size = Vector2(96, 58)
	button.add_theme_stylebox_override("normal", panel_style(Color.WHITE))
	button.add_theme_stylebox_override("hover", panel_style(Color("ccfbf1")))
	button.add_theme_stylebox_override("pressed", panel_style(Color("99f6e4")))
	button.add_theme_color_override("font_color", TEAL)
	button.add_theme_color_override("font_hover_color", TEAL)
	button.add_theme_color_override("font_pressed_color", TEAL)
	button.add_theme_font_size_override("font_size", 20)
	return button

func action(caption: String) -> void:
	match caption:
		"Back":
			if bridge: bridge.closeChart()
		"Reset":
			yaw = 0
			pitch = -0.25
			distance = 13
			target = Vector3.ZERO
		"Focus":
			if teeth.has(selected):
				target = teeth[selected].global_position
				distance = 7.0
		"Upper": toggle_arch(upper)
		"Lower": toggle_arch(lower)
		"Labels":
			show_labels = not show_labels
			for body in teeth.values(): body.get_node("SlotLabel").visible = show_labels
		"Roots":
			show_roots = not show_roots
			for body in teeth.values():
				if body.has_node("Roots"): body.get_node("Roots").visible = show_roots
		"−": distance = clamp(distance * 1.1, 7.0, 24.0)
		"+": distance = clamp(distance / 1.1, 7.0, 24.0)
	update_camera()

func toggle_arch(arch: Node3D) -> void:
	arch.visible = not arch.visible
	# Visibility alone does not disable physics picking; keep hidden arches out of rays.
	for child in arch.get_children():
		if child is StaticBody3D: child.collision_layer = 1 if arch.visible else 0
	if teeth.has(selected) and teeth[selected].get_parent() == arch and not arch.visible:
		selected = ""
		selected_surface = ""
		selected_label.text = "Select a tooth"
		assess_button.disabled = true
		for child in surface_row.get_children(): child.queue_free()

func update_camera() -> void:
	rig.rotation = Vector3(pitch, yaw, 0)
	camera.position = target + Vector3(0, distance * 0.42, distance)
	camera.look_at(target)

func edit_selected() -> void:
	if not input_valid or read_only or selected == "" or not bridge: return
	if selected_surface != "": bridge.selectSurface(selected, selected_surface)
	else: bridge.selectTooth(selected)

func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			fingers[event.index] = event.position
			touch_origin = event.position
			if fingers.size() == 1: moved = false
		else:
			fingers.erase(event.index)
			if not moved and event.position.distance_to(touch_origin) < 14: pending_pick = event.position
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
			if new_distance > 8 and old_distance > 8: distance = clamp(distance * old_distance / new_distance, 7.0, 24.0)
			pan(center - old_center)
		else:
			fingers[event.index] = event.position
			yaw += event.relative.x * 0.005
			pitch = clamp(pitch + event.relative.y * 0.005, -1.25, 1.25)
		update_camera()
	elif event is InputEventMagnifyGesture:
		distance = clamp(distance / event.factor, 7.0, 24.0)
		update_camera()
	elif event is InputEventMouseMotion and Input.is_mouse_button_pressed(MOUSE_BUTTON_LEFT):
		moved = moved or event.relative.length() > 2
		yaw += event.relative.x * 0.005
		pitch = clamp(pitch + event.relative.y * 0.005, -1.25, 1.25)
		update_camera()
	elif event is InputEventMouseMotion and Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT):
		pan(event.relative)
		update_camera()
	elif event is InputEventMouseButton:
		if event.button_index == MOUSE_BUTTON_LEFT:
			if event.pressed: moved = false
			elif not moved: pending_pick = event.position
		elif event.pressed and event.button_index == MOUSE_BUTTON_WHEEL_UP: action("+")
		elif event.pressed and event.button_index == MOUSE_BUTTON_WHEEL_DOWN: action("−")

func _input(event: InputEvent) -> void:
	# A touch may begin on the model and end over a GUI button, whose release is
	# consumed before _unhandled_input. Always release its tracked finger.
	if event is InputEventScreenTouch and not event.pressed: fingers.erase(event.index)

func _notification(what: int) -> void:
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
	var body: Node3D = result.collider
	selected = body.get_meta("toothKey")
	selected_surface = ""
	var state: Dictionary = chart.get("teeth", {}).get(selected, {})
	selected_label.text = "Tooth " + selected + "  ·  " + state.get("presence", "unknown").replace("_", " ")
	refresh_details(state)
	if selected_halo: selected_halo.queue_free()
	selected_halo = MeshInstance3D.new()
	var ring := TorusMesh.new()
	ring.inner_radius = 0.42
	ring.outer_radius = 0.48
	ring.rings = 24
	ring.ring_segments = 12
	selected_halo.mesh = ring
	var size: Vector3 = body.get_meta("dimensions")
	selected_halo.scale = Vector3(size.x * 1.2, 0.45, size.z * 1.2)
	selected_halo.position.y = body.get_meta("signY") * size.y * 0.48
	selected_halo.material_override = mat(TEAL)
	body.add_child(selected_halo)
	for child in surface_row.get_children(): child.queue_free()
	var last := int(selected[1])
	for surface in ["Whole tooth", "M", "D", "F", "L", "I" if last <= 3 else "O"]:
		var button := ui_button(surface)
		button.pressed.connect(func():
			selected_surface = "" if surface == "Whole tooth" else surface
			refresh_details(state)
		)
		surface_row.add_child(button)
	assess_button.disabled = read_only

func refresh_details(state: Dictionary) -> void:
	var lines: Array[String] = ["Assessment: " + state.get("assessment", "not_assessed").replace("_", " ")]
	if state.get("replacement", "none") != "none": lines.append("Replacement: " + state.get("replacement", "").replace("_", " "))
	var facts: Dictionary = state if selected_surface == "" else state.get("surfaces", {}).get(selected_surface, {})
	if selected_surface != "": lines.append("Surface " + selected_surface + ": " + facts.get("assessment", "not_assessed").replace("_", " "))
	if not facts.get("findings", []).is_empty(): lines.append("Findings: " + ", ".join(facts.get("findings", [])))
	if not facts.get("restorations", []).is_empty(): lines.append("Restorations: " + ", ".join(facts.get("restorations", [])))
	if not state.get("treatmentStates", []).is_empty(): lines.append("Open care: " + ", ".join(state.get("treatmentStates", [])))
	if selected_surface == "": lines.append("Choose a surface below, or assess the whole tooth.")
	details_label.text = "\n".join(lines)
	assess_button.text = "Assess tooth " + selected + (" · " + selected_surface if selected_surface != "" else "")
	if read_only: assess_button.text = "Historical chart · read only"
