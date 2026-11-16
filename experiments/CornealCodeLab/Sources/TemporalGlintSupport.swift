import Foundation

enum TemporalGlintSupport {
    /// 只增强已有当前像素峰且前后邻帧均支持的位置；不会把空白图像插值成测量。
    static func refine(_ input: [GlintEstimate]) -> [GlintEstimate] {
        var output = input
        for i in input.indices {
            let frame = input[i]
            guard !frame.source.measured,frame.emissionPhase == .on,(frame.snr ?? 0)>=3,
                  (frame.candidateConfidence ?? 0)>=0.15,frame.ambiguity<=0.65,
                  let candidate = frame.candidatePoint,let relative = frame.candidateIrisRelative,let prediction = frame.imagePoint,
                  hypot(candidate.x-prediction.x,candidate.y-prediction.y)<=max(2,(frame.expectedDiameterPixels ?? 2)*1.25) else { continue }
            var past = 0,future = 0
            for j in max(0,i-3)...min(input.count-1,i+3) where j != i {
                let neighbor = input[j]
                guard abs(neighbor.hostTime-frame.hostTime)<=0.12,neighbor.source.measured,neighbor.confidence>=0.4,
                      neighbor.emissionPhase == .on,let p = neighbor.irisRelative,hypot(p.x-relative.x,p.y-relative.y)<0.07 else { continue }
                if j<i { past += 1 } else { future += 1 }
            }
            guard past>0,future>0 else { continue }
            output[i].imagePoint = candidate; output[i].measurementPoint = candidate; output[i].irisRelative = relative
            output[i].source = .weak
            output[i].confidence = min(0.55,max(0.25,(frame.candidateConfidence ?? 0)*1.3))
            output[i].observedDiameterPixels = frame.candidateDiameter
            output[i].temporalSupportFrames = past+future
            output[i].signal = nil
        }
        return output
    }
}
