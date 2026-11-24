import Foundation

// 来自 WebEyeTrack 浏览器版 gaze_mlp。编码器冻结，端上只更新这三层。
struct WebEyeTrackHead: Codable {
    struct Layer: Codable {
        var kernel: [Float]
        var bias: [Float]

        func zeroed() -> Layer {
            Layer(kernel: Array(repeating: 0, count: kernel.count), bias: Array(repeating: 0, count: bias.count))
        }
    }

    static let featureCount = 518
    var layers: [Layer]

    var valid: Bool {
        let shape = [(Self.featureCount, 16), (16, 16), (16, 2)]
        return layers.count == shape.count && zip(layers, shape).allSatisfy { layer, size in
            layer.kernel.count == size.0 * size.1 && layer.bias.count == size.1 &&
            layer.kernel.allSatisfy(\.isFinite) && layer.bias.allSatisfy(\.isFinite)
        }
    }

    fileprivate func forward(_ input: [Float]) -> [[Float]] {
        var activations = [input]
        for index in layers.indices {
            let layer = layers[index]
            let last = activations[index]
            let width = layer.bias.count
            var output = layer.bias
            for i in last.indices {
                for j in 0..<width { output[j] += last[i] * layer.kernel[i * width + j] }
            }
            activations.append(index == layers.count - 1 ? output : output.map { max(0, $0) })
        }
        return activations
    }

    func predict(_ input: [Float]) -> GazePoint {
        let output = forward(input)[layers.count]
        return GazePoint(x: Double(output[0]), y: Double(output[1]))
    }
}

struct WebEyeTrackPersonalization: Codable {
    struct Sample {
        let features: [Float]
        let target: GazePoint
    }

    let head: WebEyeTrackHead
    let affine: GazeCalibration
    let sampleCount: Int
    let learningRate: Float
    let beta1: Float
    let beta2: Float
    let epsilon: Float
    let steps: Int
    let supportMSEBefore: Double
    let supportMSEAfter: Double

    static func fit(_ samples: [Sample], baseHead: WebEyeTrackHead, steps: Int = 1) -> Self? {
        guard (4...9).contains(samples.count), steps >= 1, baseHead.valid, samples.allSatisfy({
            $0.features.count == WebEyeTrackHead.featureCount && $0.features.allSatisfy(\.isFinite) && $0.target.onScreen
        }) else { return nil }

        let centered = samples.map { GazePoint(x: $0.target.x - 0.5, y: $0.target.y - 0.5) }
        let pairs = zip(samples, centered).map { (input: baseHead.predict($0.features), target: $1) }
        // 加极小岭正则 0.002，防止 4-5 点近共线时仿射矩阵病态外推。
        guard pairs.allSatisfy({ $0.input.finite }),
              let affine = GazeCalibration.fit(pairs, minimumTargets: 4, ridge: 0.002) else { return nil }

        let rate: Float = 1e-5, beta1: Float = 0.85, beta2: Float = 0.9, epsilon: Float = 1e-8
        let x0 = Float(affine.x[0] / affine.inputScale.x), x1 = Float(affine.x[1] / affine.inputScale.y)
        let y0 = Float(affine.y[0] / affine.inputScale.x), y1 = Float(affine.y[1] / affine.inputScale.y)
        let count = Float(samples.count)

        var current = baseHead
        var mState = baseHead.layers.map { $0.zeroed() }
        var vState = baseHead.layers.map { $0.zeroed() }
        var before = 0.0

        for step in 1...steps {
            var gradients = current.layers.map { $0.zeroed() }
            var lossSum = 0.0
            for (sample, target) in zip(samples, centered) {
                let activations = current.forward(sample.features)
                let output = activations[current.layers.count]
                let mapped = affine.apply(GazePoint(x: Double(output[0]), y: Double(output[1])))
                let dx = mapped.x - target.x, dy = mapped.y - target.y
                lossSum += dx * dx + dy * dy
                var error = [(Float(dx) * x0 + Float(dy) * y0) / count,
                             (Float(dx) * x1 + Float(dy) * y1) / count]
                for index in current.layers.indices.reversed() {
                    let layer = current.layers[index]
                    let input = activations[index]
                    let width = error.count
                    var inputError = Array(repeating: Float.zero, count: input.count)
                    for j in 0..<width { gradients[index].bias[j] += error[j] }
                    for i in input.indices {
                        for j in 0..<width {
                            let k = i * width + j
                            gradients[index].kernel[k] += input[i] * error[j]
                            inputError[i] += layer.kernel[k] * error[j]
                        }
                        if input[i] <= 0 { inputError[i] = 0 }
                    }
                    error = inputError
                }
            }
            if step == 1 { before = lossSum }

            let b1Pow = 1 - pow(beta1, Float(step))
            let b2Pow = 1 - pow(beta2, Float(step))
            func adamStep(_ weights: inout [Float], _ m: inout [Float], _ v: inout [Float], _ grad: [Float]) {
                for i in weights.indices {
                    let g = grad[i]
                    m[i] = beta1 * m[i] + (1 - beta1) * g
                    v[i] = beta2 * v[i] + (1 - beta2) * g * g
                    let mHat = m[i] / b1Pow
                    let vHat = v[i] / b2Pow
                    weights[i] -= rate * mHat / (vHat.squareRoot() + epsilon)
                }
            }
            for index in current.layers.indices {
                adamStep(&current.layers[index].kernel, &mState[index].kernel, &vState[index].kernel, gradients[index].kernel)
                adamStep(&current.layers[index].bias, &mState[index].bias, &vState[index].bias, gradients[index].bias)
            }
        }

        guard current.valid else { return nil }
        let after = zip(samples, centered).reduce(0.0) { sum, row in
            let mapped = affine.apply(current.predict(row.0.features))
            let dx = mapped.x - row.1.x, dy = mapped.y - row.1.y
            return sum + dx * dx + dy * dy
        }
        let denominator = Double(samples.count * 2)
        guard before.isFinite, after.isFinite else { return nil }
        return Self(head: current, affine: affine, sampleCount: samples.count, learningRate: rate,
                    beta1: beta1, beta2: beta2, epsilon: epsilon, steps: steps,
                    supportMSEBefore: before / denominator, supportMSEAfter: after / denominator)
    }
}

// 官方二维常速度 Kalman 的 X、Y 轴独立，可分别用 2×2 协方差计算。
struct WebEyeTrackFilter {
    private struct Axis {
        var position = 0.0
        var velocity = 0.0
        var positionVariance = 1.0
        var velocityVariance = 1.0
        var covariance = 0.0

        mutating func step(_ measurement: Double) -> Double {
            position += velocity
            let p = positionVariance + 2 * covariance + velocityVariance + 1e-4
            let cross = covariance + velocityVariance
            let v = velocityVariance + 1e-4
            let innovation = measurement - position
            let positionGain = p / (p + 1e-2)
            let velocityGain = cross / (p + 1e-2)
            position += positionGain * innovation
            velocity += velocityGain * innovation
            positionVariance = (1 - positionGain) * p
            covariance = (1 - positionGain) * cross
            velocityVariance = v - velocityGain * cross
            return position
        }
    }

    private var x = Axis()
    private var y = Axis()

    mutating func reset() {
        x = Axis()
        y = Axis()
    }

    mutating func step(_ point: GazePoint) -> GazePoint {
        GazePoint(x: x.step(point.x), y: y.step(point.y))
    }
}
