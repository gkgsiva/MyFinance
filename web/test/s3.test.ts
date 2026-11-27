import { describe, expect, it } from 'vitest';
import { encodeKey, signGet } from '../src/store/s3';

describe('S3 signing', () => {
  it('matches the GET Object example in the AWS Signature Version 4 documentation', async () => {
    const h = await signGet(
      { bucket: 'examplebucket', region: 'us-east-1', accessKeyId: 'AKIAIOSFODNN7EXAMPLE', secretAccessKey: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY' },
      'examplebucket.s3.amazonaws.com', '/test.txt', new Date('2013-05-24T00:00:00Z'), { range: 'bytes=0-9' },
    );
    expect(h.Authorization).toBe(
      'AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41',
    );
  });
  it('encodes keys as S3 does', () => {
    expect(encodeKey('processed/files/a b(1).json')).toBe('processed/files/a%20b%281%29.json');
  });
});
