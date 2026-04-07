using UnityEngine;

public class Rotator : MonoBehaviour
{
    void Update()
    {
        transform.Rotate(0f, 45f * Time.deltaTime, 0f);
    }
}
