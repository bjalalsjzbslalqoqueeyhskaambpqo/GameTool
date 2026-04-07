extends MeshInstance3D

@export var rotation_speed_degrees: float = 20.0

func _process(delta: float) -> void:
	rotate_y(deg_to_rad(rotation_speed_degrees) * delta)
