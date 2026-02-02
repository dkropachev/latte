//! Request compression support for DynamoDB client.

use crate::config::{DynamoDbCompression, RequestCompressionConf};
use flate2::write::GzEncoder;
use flate2::Compression;
use std::io::Write;

/// Request compression configuration wrapper with compression logic.
#[derive(Debug, Clone, Default)]
pub struct RequestCompressionConfig {
    conf: RequestCompressionConf,
}

impl From<RequestCompressionConf> for RequestCompressionConfig {
    fn from(conf: RequestCompressionConf) -> Self {
        Self { conf }
    }
}

impl RequestCompressionConfig {
    /// Create a new request compression configuration.
    pub fn new(algorithm: DynamoDbCompression, min_size: usize) -> Self {
        Self {
            conf: RequestCompressionConf {
                algorithm,
                min_size,
            },
        }
    }

    /// Returns true if compression is enabled.
    pub fn is_enabled(&self) -> bool {
        self.conf.is_enabled()
    }

    /// Returns the compression algorithm.
    pub fn algorithm(&self) -> DynamoDbCompression {
        self.conf.algorithm
    }

    /// Returns the minimum body size for compression.
    pub fn min_size(&self) -> usize {
        self.conf.min_size
    }

    /// Returns true if the given body size should be compressed.
    pub fn should_compress(&self, body_size: usize) -> bool {
        self.is_enabled() && body_size >= self.conf.min_size
    }

    /// Compress the given data using the configured algorithm.
    /// Returns None if compression is disabled or body is too small.
    pub fn compress(&self, data: &[u8]) -> Option<CompressedData> {
        if !self.should_compress(data.len()) {
            return None;
        }

        match self.conf.algorithm {
            DynamoDbCompression::None => None,
            DynamoDbCompression::Gzip => {
                let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
                if encoder.write_all(data).is_err() {
                    return None;
                }
                match encoder.finish() {
                    Ok(compressed) => {
                        // Only use compressed data if it's actually smaller
                        if compressed.len() < data.len() {
                            Some(CompressedData {
                                data: compressed,
                                encoding: "gzip",
                            })
                        } else {
                            None
                        }
                    }
                    Err(_) => None,
                }
            }
        }
    }

    /// Returns the Content-Encoding header value for the configured algorithm.
    pub fn content_encoding(&self) -> Option<&'static str> {
        match self.conf.algorithm {
            DynamoDbCompression::None => None,
            DynamoDbCompression::Gzip => Some("gzip"),
        }
    }
}

/// Compressed data with its encoding type.
#[derive(Debug)]
pub struct CompressedData {
    /// The compressed bytes.
    pub data: Vec<u8>,
    /// The content encoding (e.g., "gzip").
    pub encoding: &'static str,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compression_disabled_by_default() {
        let config = RequestCompressionConfig::default();
        assert!(!config.is_enabled());
        assert!(!config.should_compress(2048));
    }

    #[test]
    fn test_compression_min_size() {
        let config = RequestCompressionConfig::new(DynamoDbCompression::Gzip, 1024);
        assert!(config.is_enabled());
        assert!(!config.should_compress(512));
        assert!(config.should_compress(1024));
        assert!(config.should_compress(2048));
    }

    #[test]
    fn test_gzip_compression() {
        let config = RequestCompressionConfig::new(DynamoDbCompression::Gzip, 100);

        // Create some compressible data (repeated pattern)
        let data = "Hello, World! ".repeat(100);
        let data_bytes = data.as_bytes();

        let result = config.compress(data_bytes);
        assert!(result.is_some());

        let compressed = result.unwrap();
        assert_eq!(compressed.encoding, "gzip");
        assert!(compressed.data.len() < data_bytes.len());
    }

    #[test]
    fn test_no_compression_for_small_data() {
        let config = RequestCompressionConfig::new(DynamoDbCompression::Gzip, 1024);

        let data = b"small data";
        let result = config.compress(data);
        assert!(result.is_none());
    }

    #[test]
    fn test_content_encoding() {
        assert_eq!(
            RequestCompressionConfig::new(DynamoDbCompression::None, 0).content_encoding(),
            None
        );
        assert_eq!(
            RequestCompressionConfig::new(DynamoDbCompression::Gzip, 0).content_encoding(),
            Some("gzip")
        );
    }

    #[test]
    fn test_from_request_compression_conf() {
        let conf = RequestCompressionConf {
            algorithm: DynamoDbCompression::Gzip,
            min_size: 512,
        };
        let config = RequestCompressionConfig::from(conf);
        assert!(config.is_enabled());
        assert_eq!(config.algorithm(), DynamoDbCompression::Gzip);
        assert_eq!(config.min_size(), 512);
    }
}
