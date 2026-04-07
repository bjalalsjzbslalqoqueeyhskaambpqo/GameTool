using UnityEngine;

public class Main : MonoBehaviour
{
    void Update()
    {
        transform.Rotate(0, 30 * Time.deltaTime, 0);
    }
}
