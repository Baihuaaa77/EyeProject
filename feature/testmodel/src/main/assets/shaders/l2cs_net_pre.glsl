#version 310 es
// block
layout (local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

// 输入数据绑定到0
layout (binding = 0) uniform sampler2D inputTexture;

// 输出数据全局内存块定义
layout(std430, binding = 1) buffer PreData {
    float data[];
} pre_data;

// imagenet归一化参数
const vec3 IMAGENET_MEAN = vec3(0.485, 0.456, 0.406);
const vec3 IMAGENET_STD = vec3(0.229, 0.224, 0.225);

// 传参，输出图像大小
uniform int out_height;
uniform int out_width;

void main() {
    // 当前线程id
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);

    if (pos.x < out_width && pos.y < out_height) {
        // 输出纹理位置
        vec2 tex_coord = vec2(float(pos.x) / float(out_width - 1),
                              float(pos.y) / float(out_height - 1));
        
        // 采样像素点-自动插值
        vec4 c = texture(inputTexture, tex_coord);   // [0,1] = pixel/255

        // 归一化重排映射到输出
        int px = pos.y * out_width + pos.x;
        int plane = out_width * out_height;
        pre_data.data[0 * plane + px] = (c.r - IMAGENET_MEAN.r) / IMAGENET_STD.r;
        pre_data.data[1 * plane + px] = (c.g - IMAGENET_MEAN.g) / IMAGENET_STD.g;
        pre_data.data[2 * plane + px] = (c.b - IMAGENET_MEAN.b) / IMAGENET_STD.b;
    }
}

