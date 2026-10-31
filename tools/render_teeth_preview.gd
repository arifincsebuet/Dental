extends SceneTree
## Capture the actual project scene using synthetic, non-patient chart data.
## Run with Godot 4.5.1: --path app/src/main/assets --script <absolute path to this file>.

func _initialize() -> void:
	call_deferred("capture")

func capture() -> void:
	root.size = Vector2i(1600, 1300)
	root.position = Vector2i(-6000, -6000)
	var scene: Node3D = load("res://chart.tscn").instantiate()
	root.add_child(scene)
	for child in scene.get_children():
		if child is CanvasLayer: child.visible = false
	scene.gum_materials.clear()
	for arch: Node3D in [scene.upper, scene.lower]:
		for child in arch.get_children():
			arch.remove_child(child)
			child.free()
	scene.teeth.clear()
	var states: Dictionary = {}
	for quadrant in range(1, 5):
		for position in range(1, 9):
			states[str(quadrant) + str(position)] = {"assessment": "assessed", "presence": "present"}
	states["16"]["surfaces"] = {"O": {"assessment": "assessed", "findings": ["caries"], "restorations": []}}
	states["16"]["treatmentStates"] = ["planned"]
	states["36"]["surfaces"] = {"O": {"assessment": "assessed", "findings": [], "restorations": ["composite"]}}
	scene.chart = {"schemaVersion": 2, "catalogVersion": "fdi-v1", "dentition": "permanent", "chartVersion": 1, "teeth": states}
	scene.build_inventory()
	# The chart eases its camera toward targets; set both so the capture is immediate.
	scene.yaw = -0.42
	scene.pitch = 0.12
	scene.distance = 12.7
	scene.cur_yaw = scene.yaw
	scene.cur_pitch = scene.pitch
	scene.cur_distance = scene.distance
	scene.update_camera()
	var canvas := CanvasLayer.new()
	root.add_child(canvas)
	var title := Label.new()
	title.text = "DENTAL CARE"
	title.position = Vector2(70, 42)
	title.add_theme_font_size_override("font_size", 44)
	title.add_theme_color_override("font_color", Color("0f766e"))
	canvas.add_child(title)
	var subtitle := Label.new()
	subtitle.text = "3D dental chart · rendered from the project's Godot scene"
	subtitle.position = Vector2(72, 106)
	subtitle.add_theme_font_size_override("font_size", 24)
	subtitle.add_theme_color_override("font_color", Color("475569"))
	canvas.add_child(subtitle)
	var footer := Label.new()
	footer.text = "Permanent dentition · 32 FDI slots · illustrative chart data\nAmber: finding   ·   Blue: restoration   ·   +: planned care"
	footer.position = Vector2(72, 1185)
	footer.add_theme_font_size_override("font_size", 23)
	footer.add_theme_color_override("font_color", Color("334155"))
	canvas.add_child(footer)
	for frame in range(5):
		await RenderingServer.frame_post_draw
	var destination: String = OS.get_cmdline_user_args()[0]
	var result := root.get_texture().get_image().save_png(destination)
	print("PREVIEW_RESULT=", result, " FILE=", destination)
	quit(result)
