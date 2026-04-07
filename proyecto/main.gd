extends Node3D

func _process(delta: float) -> void:
    $MeshInstance3D.rotation.y += delta
