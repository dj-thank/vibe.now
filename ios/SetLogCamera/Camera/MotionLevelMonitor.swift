import AVFoundation
import UIKit

actor VideoThumbnailGenerator {
    private let cache = NSCache<NSURL, UIImage>()

    func image(for url: URL) async -> UIImage? {
        if let cached = cache.object(forKey: url as NSURL) {
            return cached
        }
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 640, height: 640)
        do {
            let image = try await generator.image(at: CMTime(seconds: 0.05, preferredTimescale: 600)).image
            let result = UIImage(cgImage: image)
            cache.setObject(result, forKey: url as NSURL)
            return result
        } catch {
            return nil
        }
    }
}
