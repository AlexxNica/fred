/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;

import net.i2p.util.NativeBigInteger;
import freenet.crypt.BlockCipher;
import freenet.crypt.DHGroup;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.DiffieHellman;
import freenet.crypt.DiffieHellmanLightContext;
import freenet.crypt.ECDH;
import freenet.crypt.ECDHLightContext;
import freenet.crypt.ECDSA;
import freenet.crypt.Global;
import freenet.crypt.HMAC;
import freenet.crypt.KeyAgreementSchemeContext;
import freenet.crypt.PCFBMode;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.Util;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.AddressTracker;
import freenet.io.AddressTracker.Status;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.IncomingPacketFilter.DECODED;
import freenet.io.comm.PacketSocketHandler;
import freenet.io.comm.Peer;
import freenet.io.comm.Peer.LocalAddressException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.io.comm.SocketHandler;
import freenet.l10n.NodeL10n;
import freenet.node.OpennetManager.ConnectionType;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.support.ByteArrayWrapper;
import freenet.support.Fields;
import freenet.support.HexUtil;
import freenet.support.LRUMap;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.SimpleFieldSet;
import freenet.support.TimeUtil;
import freenet.support.io.FileUtil;
import freenet.support.io.InetAddressComparator;
import freenet.support.io.NativeThread;
import freenet.support.DerUtils;

/**
 * @author amphibian
 *
 * Encodes and decodes packets for FNP.
 *
 * This includes encryption, authentication, and may later
 * include queueing etc. (that may require some interface
 * changes in IncomingPacketFilter).
 */
public class FNPPacketMangler implements OutgoingPacketMangler {
	private static volatile boolean logMINOR;
	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	private final Node node;
	private final NodeCrypto crypto;
	private final PacketSocketHandler sock;
	/**
	 * Objects cached during JFK message exchange: JFK(3,4) with authenticator as key
	 * The messages are cached in hashmaps because the message retrieval from the cache
	 * can be performed in constant time( given the key)
	 */
	private final HashMap<ByteArrayWrapper, byte[]> authenticatorCache;
	/** The following is used in the HMAC calculation of JFK message3 and message4 */
	private static final byte[] JFK_PREFIX_INITIATOR, JFK_PREFIX_RESPONDER;
	static {
		byte[] I = null,R = null;
		try {
			I = "I".getBytes("UTF-8");
			R = "R".getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new Error("Impossible: JVM doesn't support UTF-8: " + e, e);
		}

		JFK_PREFIX_INITIATOR = I;
		JFK_PREFIX_RESPONDER = R;
	}

	/* How often shall we generate a new exponential and add it to the FIFO? */
	public final static int DH_GENERATION_INTERVAL = 30000; // 30sec
	/* How big is the FIFO? */
	public final static int DH_CONTEXT_BUFFER_SIZE = 20;
	/*
	* The FIFO itself
	* Get a lock on dhContextFIFO before touching it!
	*/
	private final LinkedList<DiffieHellmanLightContext> dhContextFIFO = new LinkedList<DiffieHellmanLightContext>();
	/* The element which is about to be prunned from the FIFO */
	private DiffieHellmanLightContext dhContextToBePrunned = null;
	private static final DHGroup dhGroupToUse = Global.DHgroupA;
	private long jfkDHLastGenerationTimestamp = 0;
	
	private final LinkedList<ECDHLightContext> ecdhContextFIFO = new LinkedList<ECDHLightContext>();
    private ECDHLightContext ecdhContextToBePrunned;
    private static final ECDH.Curves ecdhCurveToUse = ECDH.Curves.P256;
	private long jfkECDHLastGenerationTimestamp = 0;

	protected static final int NONCE_SIZE = 8;
	private static final int RANDOM_BYTES_LENGTH = 12;
	private static final int HASH_LENGTH = SHA256.getDigestLength();
	/** The size of the key used to authenticate the hmac */
	private static final int TRANSIENT_KEY_SIZE = HASH_LENGTH;
	/** The key used to authenticate the hmac */
	private final byte[] transientKey = new byte[TRANSIENT_KEY_SIZE];
	public static final int TRANSIENT_KEY_REKEYING_MIN_INTERVAL = 30*60*1000;
	/** The rekeying interval for the session key (keytrackers) */
	public static final int SESSION_KEY_REKEYING_INTERVAL = 60*60*1000;
	/** The max amount of time we will accept to use the current tracker when it should have been replaced */
	public static final int MAX_SESSION_KEY_REKEYING_DELAY = 5*60*1000;
	/** The amount of data sent before we ask for a rekey */
	public static final int AMOUNT_OF_BYTES_ALLOWED_BEFORE_WE_REKEY = 1024 * 1024 * 1024;
	/** The Runnable in charge of rekeying on a regular basis */
	private final Runnable transientKeyRekeyer = new Runnable() {
		@Override
		public void run() {
			maybeResetTransientKey();
		}
	};
	/** Minimum headers overhead */
	private static final int HEADERS_LENGTH_MINIMUM =
		4 + // sequence number
		RANDOM_BYTES_LENGTH + // random junk
		1 + // version
		1 + // assume seqno != -1; otherwise would be 4
		4 + // other side's seqno
		1 + // number of acks
		0 + // assume no acks
		1 + // number of resend reqs
		0 + // assume no resend requests
		1 + // number of ack requests
		0 + // assume no ack requests
		1 + // no forgotten packets
		HASH_LENGTH + // hash
		1; // number of messages
	/** Headers overhead if there is one message and no acks. */
	static public final int HEADERS_LENGTH_ONE_MESSAGE =
		HEADERS_LENGTH_MINIMUM + 2; // 2 bytes = length of message. rest is the same.

	final int fullHeadersLengthMinimum;
	final int fullHeadersLengthOneMessage;
        
        private long lastConnectivityStatusUpdate;
        private Status lastConnectivityStatus;


	public FNPPacketMangler(Node node, NodeCrypto crypt, PacketSocketHandler sock) {
		this.node = node;
		this.crypto = crypt;
		this.sock = sock;
		authenticatorCache = new HashMap<ByteArrayWrapper, byte[]>();
		fullHeadersLengthMinimum = HEADERS_LENGTH_MINIMUM + sock.getHeadersLength();
		fullHeadersLengthOneMessage = HEADERS_LENGTH_ONE_MESSAGE + sock.getHeadersLength();
	}

	/**
	 * Start up the FNPPacketMangler. By the time this is called, all objects will have been constructed,
	 * but not all will have been started yet.
	 */
	public void start() {
		// Run it directly so that the transient key is set.
		maybeResetTransientKey();
		// Fill the DH FIFO on-thread
		for(int i=0;i<DH_CONTEXT_BUFFER_SIZE;i++) {
			_fillJFKDHFIFO();
		}
		if(!NodeStarter.bcProvLoadFailed()) {
			for(int i=0;i<DH_CONTEXT_BUFFER_SIZE;i++) {
				_fillJFKECDHFIFO();
			}
		}
	}

	/**
	 * Packet format:
	 *
	 * E_session_ecb(
	 *         4 bytes:  sequence number XOR first 4 bytes of node identity
	 *         12 bytes: first 12 bytes of H(data)
	 *         )
	 * E_session_ecb(
	 *         16 bytes: bytes 12-28 of H(data)
	 *         ) XOR previous ciphertext XOR previous plaintext
	 * 4 bytes: bytes 28-32 of H(data) XOR bytes 0-4 of H(data)
	 * E_session_pcfb(data) // IV = first 32 bytes of packet
	 *
	 */

	public DECODED process(byte[] buf, int offset, int length, Peer peer, long now) {
		/**
		 * Look up the Peer.
		 * If we know it, check the packet with that key.
		 * Otherwise try all of them (on the theory that nodes
		 * occasionally change their IP addresses).
		 */
		PeerNode opn = node.peers.getByPeer(peer);
		return process(buf, offset, length, peer, opn, now);
	}

	/**
	 * Decrypt and authenticate packet.
	 * Then feed it to USM.checkFilters.
	 * Packets generated should have a PeerNode on them.
	 * Note that the buffer can be modified by this method.
	 */
	public DECODED process(byte[] buf, int offset, int length, Peer peer, PeerNode opn, long now) {

		if(opn != null && opn.getOutgoingMangler() != this) {
			Logger.error(this, "Apparently contacted by "+opn+") on "+this, new Exception("error"));
			opn = null;
		}
		boolean wantAnonAuth = crypto.wantAnonAuth();

		if(opn != null) {
			if(logMINOR) Logger.minor(this, "Trying exact match");
			if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2 && !node.isStopping()) {
				// Might be an auth packet
				if(tryProcessAuth(buf, offset, length, opn, peer, false, now)) {
					return DECODED.DECODED;
				}
				// Might be a reply to us sending an anon auth packet.
				// I.e. we are not the seednode, they are.
				if(tryProcessAuthAnonReply(buf, offset, length, opn, peer, now)) {
					return DECODED.DECODED;
				}
			}
		}
		PeerNode[] peers = crypto.getPeerNodes();
		if(node.isStopping()) return DECODED.SHUTTING_DOWN;
		// Disconnected node connecting on a new IP address?
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 2) {
			for(PeerNode pn: peers) {
				if(pn == opn) continue;
				if(logDEBUG)
					Logger.debug(this, "Trying auth with "+pn);
				if(tryProcessAuth(buf, offset, length, pn, peer,false, now)) {
					return DECODED.DECODED;
				}
				if(pn.handshakeUnknownInitiator()) {
					// Might be a reply to us sending an anon auth packet.
					// I.e. we are not the seednode, they are.
					if(tryProcessAuthAnonReply(buf, offset, length, pn, peer, now)) {
						return DECODED.DECODED;
					}
				}
			}
		}
		
		boolean wantAnonAuthChangeIP = wantAnonAuth && crypto.wantAnonAuthChangeIP();
		
		if(wantAnonAuth && wantAnonAuthChangeIP) {
			if(checkAnonAuthChangeIP(opn, buf, offset, length, peer, now)) return DECODED.DECODED;
		}

		boolean didntTryOldOpennetPeers;
		OpennetManager opennet = node.getOpennet();
		if(opennet != null) {
			// Try old opennet connections.
			if(opennet.wantPeer(null, false, true, true, ConnectionType.RECONNECT)) {
				// We want a peer.
				// Try old connections.
				for(PeerNode oldPeer: opennet.getOldPeers()) {
					if(tryProcessAuth(buf, offset, length, oldPeer, peer, true, now)) return DECODED.DECODED;
				}
				didntTryOldOpennetPeers = false;
			} else
				didntTryOldOpennetPeers = true;
		} else
			didntTryOldOpennetPeers = false;
		if(wantAnonAuth) {
			if(tryProcessAuthAnon(buf, offset, length, peer))
				return DECODED.DECODED;
		}
		
		if(wantAnonAuth && !wantAnonAuthChangeIP) {
			if(checkAnonAuthChangeIP(opn, buf, offset, length, peer, now)) {
				// This can happen when a node is upgraded from a SeedClientPeerNode to an OpennetPeerNode.
				//Logger.error(this, "Last resort match anon-auth against all anon setup peernodes succeeded - this should not happen! (It can happen if they change address)");
				return DECODED.DECODED;
			}
		}

                // Don't log too much if we are a seednode
                if(logMINOR && crypto.isOpennet && wantAnonAuth) {
                	if(!didntTryOldOpennetPeers)
                		Logger.minor(this,"Unmatchable packet from "+peer);
                } else
                    Logger.normal(this,"Unmatchable packet from "+peer);
                
                if(!didntTryOldOpennetPeers)
                	return DECODED.NOT_DECODED;
                else
                	return DECODED.DIDNT_WANT_OPENNET;
	}
	
	private boolean checkAnonAuthChangeIP(PeerNode opn, byte[] buf, int offset, int length, Peer peer, long now) {
		PeerNode[] anonPeers = crypto.getAnonSetupPeerNodes();
		if(length > Node.SYMMETRIC_KEY_LENGTH /* iv */ + HASH_LENGTH + 3) {
			for(PeerNode pn: anonPeers) {
				if(pn == opn) continue;
				if(tryProcessAuthAnonReply(buf, offset, length, pn, peer, now)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Is this a negotiation packet? If so, process it.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param pn The PeerNode we think is responsible
	 * @param peer The Peer to send a reply to
	 * @param now The time at which the packet was received
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuth(byte[] buf, int offset, int length, PeerNode pn, Peer peer, boolean oldOpennetPeer, long now) {
		BlockCipher authKey = pn.incomingSetupCipher;
		if(logDEBUG) Logger.debug(this, "Decrypt key: "+HexUtil.bytesToHex(pn.incomingSetupKey)+" for "+peer+" : "+pn+" in tryProcessAuth");
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 4) {
			if(logMINOR) {
				if(buf.length < length) {
					if(logDEBUG) Logger.debug(this, "The packet is smaller than the decrypted size: it's probably the wrong tracker ("+buf.length+'<'+length+')');
				} else {
					Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 4));
				}
			}
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
		// Then the hash, then the data
		// => Data starts at ivLength + digestLength
		// Decrypt the hash
		byte[] hash = Arrays.copyOfRange(buf, offset+ivLength, offset+ivLength+digestLength);
		pcfb.blockDecipher(hash, 0, hash.length);

		int dataStart = ivLength + digestLength + offset+2;

		int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
		int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
		int dataLength = (byte1 << 8) + byte2;
		if(logDEBUG) Logger.debug(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logDEBUG) Logger.debug(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
			return false;
		}
		// Decrypt the data
		byte[] payload = Arrays.copyOfRange(buf, dataStart, dataStart+dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		byte[] realHash = SHA256.digest(payload);

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuth(payload, pn, peer, oldOpennetPeer);
			pn.reportIncomingBytes(length);
			return true;
		} else {
			if(logDEBUG) Logger.debug(this, "Incorrect hash in tryProcessAuth for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	/**
	 * Might be an anonymous-initiator negotiation packet (i.e.
	 * we are the responder).
	 * Anonymous initiator is used for seednode connections,
	 * and will in future be used for other things for example
	 * one-side-only invites, password based invites etc.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param peer The Peer to send a reply to
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuthAnon(byte[] buf, int offset, int length, Peer peer) {
		BlockCipher authKey = crypto.getAnonSetupCipher();
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 5) {
			if(logMINOR) Logger.minor(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 5));
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
		// Then the hash, then the data
		// => Data starts at ivLength + digestLength
		// Decrypt the hash
		byte[] hash = Arrays.copyOfRange(buf, offset+ivLength, offset+ivLength+digestLength);
		pcfb.blockDecipher(hash, 0, hash.length);

		int dataStart = ivLength + digestLength + offset+2;

		int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
		int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
		int dataLength = (byte1 << 8) + byte2;
		if(logMINOR) Logger.minor(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logMINOR) Logger.minor(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuthAnon");
			return false;
		}
		// Decrypt the data
		byte[] payload = Arrays.copyOfRange(buf, dataStart, dataStart+dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		byte[] realHash = SHA256.digest(payload);

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuthAnon(payload, peer);
			return true;
		} else {
			if(logMINOR) Logger.minor(this, "Incorrect hash in tryProcessAuthAnon for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	/**
	 * Might be a reply to an anonymous-initiator negotiation
	 * packet (i.e. we are the initiator).
	 * Anonymous initiator is used for seednode connections,
	 * and will in future be used for other things for example
	 * one-side-only invites, password based invites etc.
	 * @param buf The buffer to read bytes from
	 * @param offset The offset at which to start reading
	 * @param length The number of bytes to read
	 * @param pn The PeerNode we think is responsible
	 * @param peer The Peer to send a reply to
	 * @param now The time at which the packet was received
	 * @return True if we handled a negotiation packet, false otherwise.
	 */
	private boolean tryProcessAuthAnonReply(byte[] buf, int offset, int length, PeerNode pn, Peer peer, long now) {
		BlockCipher authKey = pn.anonymousInitiatorSetupCipher;
		// Does the packet match IV E( H(data) data ) ?
		int ivLength = PCFBMode.lengthIV(authKey);
		int digestLength = HASH_LENGTH;
		if(length < digestLength + ivLength + 5) {
			if(logDEBUG) Logger.debug(this, "Too short: "+length+" should be at least "+(digestLength + ivLength + 5));
			return false;
		}
		// IV at the beginning
		PCFBMode pcfb = PCFBMode.create(authKey, buf, offset);
		// Then the hash, then the data
		// => Data starts at ivLength + digestLength
		// Decrypt the hash
		byte[] hash = Arrays.copyOfRange(buf, offset+ivLength, offset+ivLength+digestLength);
		pcfb.blockDecipher(hash, 0, hash.length);

		int dataStart = ivLength + digestLength + offset+2;

		int byte1 = ((pcfb.decipher(buf[dataStart-2])) & 0xff);
		int byte2 = ((pcfb.decipher(buf[dataStart-1])) & 0xff);
		int dataLength = (byte1 << 8) + byte2;
		if(logDEBUG) Logger.minor(this, "Data length: "+dataLength+" (1 = "+byte1+" 2 = "+byte2+ ')');
		if(dataLength > length - (ivLength+hash.length+2)) {
			if(logDEBUG) Logger.debug(this, "Invalid data length "+dataLength+" ("+(length - (ivLength+hash.length+2))+") in tryProcessAuth");
			return false;
		}
		// Decrypt the data
		byte[] payload = Arrays.copyOfRange(buf, dataStart, dataStart+dataLength);
		pcfb.blockDecipher(payload, 0, payload.length);

		byte[] realHash = SHA256.digest(payload);

		if(Arrays.equals(realHash, hash)) {
			// Got one
			processDecryptedAuthAnonReply(payload, peer, pn);
			return true;
		} else {
			if(logDEBUG) Logger.debug(this, "Incorrect hash in tryProcessAuth for "+peer+" (length="+dataLength+"): \nreal hash="+HexUtil.bytesToHex(realHash)+"\n bad hash="+HexUtil.bytesToHex(hash));
			return false;
		}
	}

	// Anonymous-initiator setup types
	/** Connect to a node hoping it will act as a seednode for us */
	static final byte SETUP_OPENNET_SEEDNODE = 1;

	/**
	 * Process an anonymous-initiator connection setup packet. For a normal setup
	 * (@see processDecryptedAuth()), we know the node that is trying to contact us.
	 * But in this case, we don't know the node yet, and we are doing a
	 * special-purpose connection setup. At the moment the only type supported is
	 * for a new node connecting to a seednode in order to announce. In future,
	 * nodes may support other anonymous-initiator connection types such as when a
	 * node (which is certain of its connectivity) issues one-time invites which
	 * allow a new node to connect to it.
	 * @param payload The decrypted payload of the packet.
	 * @param replyTo The address the packet came in from.
	 */
	private void processDecryptedAuthAnon(byte[] payload, Peer replyTo) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" length "+payload.length);

		/** Protocol version. Should be 1. */
		int version = payload[0];
		/** Negotiation type. Common to anonymous-initiator auth and normal setup.
		 *   2 = JFK.
		 *   3 = JFK, reuse PacketTracker
		 * Other types might indicate other DH variants, or even non-DH-based
		 * algorithms such as password based key setup. */
		int negType = payload[1];
		/** Packet phase. */
		int packetType = payload[2];
		/** Setup type. This is specific to anonymous-initiator setup, and specifies the
		 * purpose of the connection. At the moment it is SETUP_OPENNET_SEEDNODE to indicate
		 * we are connecting to a seednode (which doesn't know us). Invites might require
		 * a different setupType. */
		int setupType = payload[3];

		if(logMINOR) Logger.minor(this, "Received anonymous auth packet (phase="+packetType+", v="+version+", nt="+negType+", setup type="+setupType+") from "+replyTo+"");

		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}
		if(!(negType == 6 || negType == 7 || negType == 8 || negType == 9)) {
			if(negType > 9)
				Logger.error(this, "Unknown neg type: "+negType);
			else
				Logger.warning(this, "Received a setup packet with unsupported obsolete neg type: "+negType);
			return;
		}

		// Known setup types
		if(setupType != SETUP_OPENNET_SEEDNODE) {
			Logger.error(this, "Unknown setup type "+negType);
			return;
		}

		// We are the RESPONDER.
		// Therefore, we can only get packets of phase 1 and 3 here.

		if(packetType == 0) {
			// Phase 1
			processJFKMessage1(payload,4,null,replyTo, true, setupType, negType);
		} else if(packetType == 2) {
			// Phase 3
			processJFKMessage3(payload, 4, null, replyTo, false, true, setupType, negType);
		} else {
			Logger.error(this, "Invalid phase "+packetType+" for anonymous-initiator (we are the responder) from "+replyTo);
		}
	}

	private void processDecryptedAuthAnonReply(byte[] payload, Peer replyTo, PeerNode pn) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+pn+" length "+payload.length);

		/** Protocol version. Should be 1. */
		int version = payload[0];
		/** Negotiation type.
		 *   2 = JFK.
		 *   3 = JFK, reuse PacketTracker
		 * Other types might indicate other DH variants, or even non-DH-based
		 * algorithms such as password based key setup. */
		int negType = payload[1];
		/** Packet phase. */
		int packetType = payload[2];
		/** Setup type. See above. */
		int setupType = payload[3];

		if(logMINOR) Logger.minor(this, "Received anonymous auth packet (phase="+packetType+", v="+version+", nt="+negType+", setup type="+setupType+") from "+replyTo+"");

		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}
		if(!(negType == 6 || negType == 7 || negType == 8 || negType == 9)) {
			if(negType > 9)
				Logger.error(this, "Unknown neg type: "+negType);
			else
				Logger.warning(this, "Received a setup packet with unsupported obsolete neg type: "+negType);
			return;
		}

		// Known setup types
		if(setupType != SETUP_OPENNET_SEEDNODE) {
			Logger.error(this, "Unknown setup type "+negType);
			return;
		}

		// We are the INITIATOR.
		// Therefore, we can only get packets of phase 2 and 4 here.

		if(packetType == 1) {
			// Phase 2
			processJFKMessage2(payload, 4, pn, replyTo, true, setupType, negType);
		} else if(packetType == 3) {
			// Phase 4
			processJFKMessage4(payload, 4, pn, replyTo, false, true, setupType, negType);
		} else {
			Logger.error(this, "Invalid phase "+packetType+" for anonymous-initiator (we are the initiator) from "+replyTo);
		}
	}

	/**
	 * Process a decrypted, authenticated auth packet.
	 * @param payload The packet payload, after it has been decrypted.
	 */
	private void processDecryptedAuth(byte[] payload, PeerNode pn, Peer replyTo, boolean oldOpennetPeer) {
		if(logMINOR) Logger.minor(this, "Processing decrypted auth packet from "+replyTo+" for "+pn);
		if(pn.isDisabled()) {
			if(logMINOR) Logger.minor(this, "Won't connect to a disabled peer ("+pn+ ')');
			return;  // We don't connect to disabled peers
		}

		int negType = payload[1];
		int packetType = payload[2];
		int version = payload[0];

		if(logMINOR) {
			long now = System.currentTimeMillis();
			long last = pn.lastSentPacketTime();
			String delta = "never";
			if (last>0) {
				delta = TimeUtil.formatTime(now-last, 2, true)+" ago";
			}
			Logger.minor(this, "Received auth packet for "+pn.getPeer()+" (phase="+packetType+", v="+version+", nt="+negType+") (last packet sent "+delta+") from "+replyTo+"");
		}

		/* Format:
		 * 1 byte - version number (1)
		 * 1 byte - negotiation type (0 = simple DH, will not be supported when implement JFKi || 1 = StS)
		 * 1 byte - packet type (0-3)
		 */
		if(version != 1) {
			Logger.error(this, "Decrypted auth packet but invalid version: "+version);
			return;
		}

		if(negType >= 0 && negType < 6) {
			// negType 0 through 5 no longer supported, used old FNP.
			Logger.warning(this, "Old neg type "+negType+" not supported");
			return;
		} else if (negType == 6 || negType == 7 || negType == 8 || negType == 9) {
		    // negType == 9 => use ECDSA with secp256r1 instead of DSA2048
		    // negType == 8 => use ECDH with secp256r1 instead of DH1024
			// negType == 7 => same as 6, but determine the initial sequence number by hashing the identity
			// instead of negotiating it
			/*
			 * We implement Just Fast Keying key management protocol with active identity protection
			 * for the initiator and no identity protection for the responder
			 * M1:
			 * This is a straightforward DiffieHellman exponential.
			 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
			 * exponentials during different sessions while ensuring that the resulting session
			 * key will be different,can be used to differentiate between parallel sessions
			 * M2:
			 * Responder replies with a signed copy of his own exponential, a random nonce and
			 * an authenticator which provides sufficient defense against forgeries,replays
			 * We slightly deviate JFK here;we do not send any public key information as specified in the JFK docs
			 * M3:
			 * Initiator echoes the data sent by the responder including the authenticator.
			 * This helps the responder verify the authenticity of the returned data.
			 * M4:
			 * Encrypted message of the signature on both nonces, both exponentials using the same keys as in the previous message
			 */
			if(packetType<0 || packetType>3) {
				Logger.error(this,"Unknown PacketType" + packetType + "from" + replyTo + "from" +pn);
				return ;
			} else if(packetType==0) {
				/*
				 * Initiator- This is a straightforward DiffieHellman exponential.
				 * The Initiator Nonce serves two purposes;it allows the initiator to use the same
				 * exponentials during different sessions while ensuring that the resulting
				 * session key will be different,can be used to differentiate between
				 * parallel sessions
				 */
				processJFKMessage1(payload,3,pn,replyTo,false,-1,negType);

			} else if(packetType==1) {
				/*
				 * Responder replies with a signed copy of his own exponential, a random
				 * nonce and an authenticator calculated from a transient hash key private
				 * to the responder.
				 */
				processJFKMessage2(payload,3,pn,replyTo,false,-1,negType);
			} else if(packetType==2) {
				/*
				 * Initiator echoes the data sent by the responder.These messages are
				 * cached by the Responder.Receiving a duplicate message simply causes
				 * the responder to Re-transmit the corresponding message4
				 */
				processJFKMessage3(payload, 3, pn, replyTo, oldOpennetPeer, false, -1, negType);
			} else if(packetType==3) {
				/*
				 * Encrypted message of the signature on both nonces, both exponentials
				 * using the same keys as in the previous message.
				 * The signature is non-message recovering
				 */
				processJFKMessage4(payload, 3, pn, replyTo, oldOpennetPeer, false, -1, negType);
			}
		} else {
			Logger.error(this, "Decrypted auth packet but unknown negotiation type "+negType+" from "+replyTo+" possibly from "+pn);
			return;
		}
	}

	/*
	 * Initiator Method:Message1
	 * Process Message1
	 * Receive the Initiator nonce and DiffieHellman Exponential
	 * @param The packet phase number
	 * @param The peerNode we are talking to. CAN BE NULL if anonymous initiator, since we are the responder.
	 * @param The peer to which we need to send the packet
	 * @param unknownInitiator If true, we (the responder) don't know the
	 * initiator, and should check for fields which would be skipped in a
	 * normal setup where both sides know the other (indicated with * below).
	 * @param setupType The type of unknown-initiator setup.
	 *
	 * format :
	 * Ni
	 * g^i
	 * *IDr'
	 *
	 * See http://www.wisdom.weizmann.ac.il/~reingold/publications/jfk-tissec.pdf
	 * Just Fast Keying: Key Agreement In A Hostile Internet
	 * Aiello, Bellovin, Blaze, Canetti, Ioannidis, Keromytis, Reingold.
	 * ACM Transactions on Information and System Security, Vol 7 No 2, May 2004, Pages 1-30.
	 *
	 */
	private void processJFKMessage1(byte[] payload,int offset,PeerNode pn,Peer replyTo, boolean unknownInitiator, int setupType, int negType)
	{
		long t1=System.currentTimeMillis();
        int minPubkeyLength = minPubkeyLength(negType);
		if(logMINOR) Logger.minor(this, "Got a JFK(1) message, processing it - "+pn);
		// FIXME: follow the spec and send IDr' ?
		int expectedLength = offset + NONCE_SIZE + minPubkeyLength + (unknownInitiator ? NodeCrypto.IDENTITY_LENGTH : 0);
		if(payload.length < expectedLength) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(1), should be "+expectedLength);
			return;
		}
		// get Ni
		byte[] nonceInitiator = Arrays.copyOfRange(payload, offset, offset + NONCE_SIZE);
		offset += NONCE_SIZE;

		// get g^i
		byte[] hisExponential = extractPublicKey(negType, payload, offset);
		if (hisExponential == null) {
			Logger.error(this, "Cannot read public key from "+pn+": packet too short or invalid DER format");
			return;
		}
		if (hisExponential.length != minPubkeyLength) {
			expectedLength += hisExponential.length - minPubkeyLength;
			if(payload.length < expectedLength) {
				Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(1), should be "+expectedLength);
				return;
			}
		}
		if(unknownInitiator) {
			// Check IDr'
			offset += hisExponential.length;
			byte[] expectedIdentityHash = Arrays.copyOfRange(payload, offset, offset + NodeCrypto.IDENTITY_LENGTH);
			if(!Arrays.equals(expectedIdentityHash, crypto.identityHash)) {
				Logger.error(this, "Invalid unknown-initiator JFK(1), IDr' is "+HexUtil.bytesToHex(expectedIdentityHash)+" should be "+HexUtil.bytesToHex(crypto.identityHash));
				return;
			}
		}
		
		if(throttleRekey(pn, replyTo)) return;
		   

		if(negType >= 8 || DiffieHellman.checkDHExponentialValidity(this.getClass(), new NativeBigInteger(1,hisExponential))) {
			// JFK protects us from weak key attacks on ECDH, so we don't need to check.
		    try {
		    	sendJFKMessage2(nonceInitiator, hisExponential, pn, replyTo, unknownInitiator, setupType, negType);
		    } catch (NoContextsException e) {
		    	handleNoContextsException(e, NoContextsException.CONTEXT.REPLYING);
		    	return;
		    }
		} else {
		    Logger.error(this, "We can't accept the exponential "+pn+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");
		}

		long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message1 timeout error:Processing packet for "+pn);
		}
	}
	
	private long lastLoggedNoContexts = -1;
	private static int LOG_NO_CONTEXTS_INTERVAL = 60*1000;
	
	private void handleNoContextsException(NoContextsException e,
			freenet.node.FNPPacketMangler.NoContextsException.CONTEXT context) {
		if(node.getUptime() < 30*1000) {
			Logger.warning(this, "No contexts available, unable to handle or send packet ("+context+") on "+this);
			return;
		}
		// Log it immediately.
		Logger.warning(this, "No contexts available "+context+" - running out of entropy or severe CPU usage problems?");
		// More loudly periodically.
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(now < lastLoggedNoContexts + LOG_NO_CONTEXTS_INTERVAL)
				return;
			lastLoggedNoContexts = now;
		}
		logLoudErrorNoContexts();
	}

	private void logLoudErrorNoContexts() {
		// If this is happening regularly post-startup then it's unlikely that reading the disk will help.
		// FIXME localise this, give a useralert etc.
		// RNG exhaustion shouldn't happen for Windows users at all, and may not happen on Linux depending on the JVM version, so lets leave it for now.
		System.err.println("FREENET IS HAVING PROBLEMS CONNECTING: Either your CPU is overloaded or it is having trouble reading from the random number generator");
		System.err.println("If the problem is CPU usage, please shut down whatever applications are hogging the CPU.");
		if(FileUtil.detectedOS.isUnix) {
			File f = new File("/dev/hwrng");
			if(f.exists())
				System.err.println("Installing \"rngd\" might help (e.g. apt-get install rng-tools).");
			System.err.println("The best solution is to install a hardware random number generator, or use turbid or similar software to take random data from an unconnected sound card.");
			System.err.println("The quick workaround is to add \"wrapper.java.additional.4=-Djava.security.egd=file:///dev/urandom\" to your wrapper.conf.");
		}
	}

	private final LRUMap<InetAddress, Long> throttleRekeysByIP = LRUMap.createSafeMap(InetAddressComparator.COMPARATOR);

	private static final int REKEY_BY_IP_TABLE_SIZE = 1024;

	private boolean throttleRekey(PeerNode pn, Peer replyTo) {
		if(pn != null) {
			return pn.throttleRekey();
		}
		long now = System.currentTimeMillis();
		InetAddress addr = replyTo.getAddress();
		synchronized(throttleRekeysByIP) {
			Long l = throttleRekeysByIP.get(addr);
			if(l == null || l != null && now > l)
				throttleRekeysByIP.push(addr, now);
			while(throttleRekeysByIP.size() > REKEY_BY_IP_TABLE_SIZE || 
					((!throttleRekeysByIP.isEmpty()) && throttleRekeysByIP.peekValue() < now - PeerNode.THROTTLE_REKEY))
				throttleRekeysByIP.popKey();
			if(l != null && now - l < PeerNode.THROTTLE_REKEY) {
				Logger.error(this, "Two JFK(1)'s initiated by same IP within "+PeerNode.THROTTLE_REKEY+"ms");
				return true;
			}
		}
		return false;
	}

	private static final int MAX_NONCES_PER_PEER = 10;

	/*
	 * format:
	 * Ni,g^i
	 * We send IDr' only if unknownInitiator is set.
	 * @param pn The node to encrypt the message to. Cannot be null, because we are the initiator and we
	 * know the responder in all cases.
	 * @param replyTo The peer to send the actual packet to.
	 */
	private void sendJFKMessage1(PeerNode pn, Peer replyTo, boolean unknownInitiator, int setupType, int negType) throws NoContextsException {
		if(logMINOR) Logger.minor(this, "Sending a JFK(1) message to "+replyTo+" for "+pn.getPeer());
		final long now = System.currentTimeMillis();
		
		KeyAgreementSchemeContext ctx = pn.getKeyAgreementSchemeContext();
		if(negType < 8) { // Legacy DH
		    if((ctx == null) || !(ctx instanceof DiffieHellmanLightContext) || ((pn.jfkContextLifetime + DH_GENERATION_INTERVAL*DH_CONTEXT_BUFFER_SIZE) < now)) {
			    pn.jfkContextLifetime = now;
			    pn.setKeyAgreementSchemeContext(ctx = getLightDiffieHellmanContext());
		    }
		} else {
            if((ctx == null) || !(ctx instanceof ECDHLightContext) || ((pn.jfkContextLifetime + DH_GENERATION_INTERVAL*DH_CONTEXT_BUFFER_SIZE) < now)) {
                pn.jfkContextLifetime = now;
                pn.setKeyAgreementSchemeContext(ctx = getECDHLightContext());
            }
		}
		
		int offset = 0;
		byte[] nonce = new byte[NONCE_SIZE];
		byte[] myExponential = ctx.getPublicKeyNetworkFormat();
		node.random.nextBytes(nonce);

		synchronized (pn) {
			pn.jfkNoncesSent.add(nonce);
			if(pn.jfkNoncesSent.size() > MAX_NONCES_PER_PEER)
				pn.jfkNoncesSent.removeFirst();
		}

		byte[] message1 = new byte[NONCE_SIZE+myExponential.length+(unknownInitiator ? NodeCrypto.IDENTITY_LENGTH : 0)];

		System.arraycopy(nonce, 0, message1, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message1, offset, myExponential.length);

		if(unknownInitiator) {
			offset += myExponential.length;
			System.arraycopy(pn.identityHash, 0, message1, offset, pn.identityHash.length);
			sendAnonAuthPacket(1,negType,0,setupType,message1,pn,replyTo,pn.anonymousInitiatorSetupCipher);
		} else {
			sendAuthPacket(1,negType,0,message1,pn,replyTo);
		}
		long t2=System.currentTimeMillis();
		if((t2-now)>500) {
			Logger.error(this,"Message1 timeout error:Sending packet for "+pn.getPeer());
		}
	}

	/*
	 * format:
	 * Ni,Nr,g^r
	 * Signature[g^r,grpInfo(r)] - R, S
	 * Hashed JFKAuthenticator : HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 *
	 * NB: we don't send IDr nor groupinfo as we know them: even if the responder doesn't know the initiator,
	 * the initiator ALWAYS knows the responder.
	 * @param pn The node to encrypt the message for. CAN BE NULL if anonymous-initiator.
	 * @param replyTo The peer to send the packet to.
	 */
	private void sendJFKMessage2(byte[] nonceInitator, byte[] hisExponential, PeerNode pn, Peer replyTo, boolean unknownInitiator, int setupType, int negType) throws NoContextsException {
		if(logMINOR) Logger.minor(this, "Sending a JFK(2) message to "+pn);

		// g^r
		KeyAgreementSchemeContext ctx = (negType < 8 ? getLightDiffieHellmanContext() : getECDHLightContext());

		// Nr
		byte[] myNonce = new byte[NONCE_SIZE];
		node.random.nextBytes(myNonce);
	    byte[] myExponential = ctx.getPublicKeyNetworkFormat();

		byte[] sig;
		if (negType < 8) {
			sig = ctx.signature;
		} else {
			if (negType < 9) {
				// generate DSA signature on demand
				synchronized(ctx) {
					if (ctx.signature == null) {
						ctx.setSignature(SHA256withDSAsign(
									myExponential,
									crypto.getCryptoGroup().getP().toByteArray()));
					}
				}
				sig = ctx.signature;
			} else {
				sig = ctx.ecdsaSig;
			}
		}

		byte[] authenticator = macJFKAuthenticator(getTransientKey(),myExponential, hisExponential, myNonce, nonceInitator, replyTo.getAddress().getAddress());
		if(logMINOR) Logger.minor(this, "We are using the following HMAC : " + HexUtil.bytesToHex(authenticator));

		byte[] message2 = new byte[NONCE_SIZE*2+myExponential.length+
								   sig.length+
		                           HASH_LENGTH];

		int offset = 0;
		System.arraycopy(nonceInitator, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myNonce, 0, message2, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		System.arraycopy(myExponential, 0, message2, offset, myExponential.length);
		offset += myExponential.length;

		System.arraycopy(sig, 0, message2, offset, sig.length);
		offset += sig.length;

		System.arraycopy(authenticator, 0, message2, offset, HASH_LENGTH);

		if(unknownInitiator) {
			sendAnonAuthPacket(1,negType,1,setupType,message2,pn,replyTo,crypto.anonSetupCipher);
		} else {
			sendAuthPacket(1,negType,1,message2,pn,replyTo);
		}
	}

	/*
	 * Compute jfk-Authentificator: HmacSHA256 computed
	 * over the Responder exponentials and the Nonces and
	 * used by the responder to verify that the round-trip has been done
	 *
	 */
	private byte[] macJFKAuthenticator(byte[] key, byte[] gR, byte[] gI, byte[] nR, byte[] nI, byte[] address) {
		HMAC mac = HMAC.getMacWithSHA256();
		mac.init(key);
		mac.update(gR);
		mac.update(gI);
		mac.update(nR);
		mac.update(nI);
		mac.update(address);
		byte [] result = mac.doFinal();
		HMAC.returnMacWithSHA256(mac);

		return result;
	}

	/*
	 * Initiator Method:Message2
	 * @see{sendJFKMessage2} for packet format details.
	 * Note that this packet is exactly the same for known initiator as for unknown initiator.
	 *
	 * @param payload The buffer containing the decrypted auth packet.
	 * @param inputOffset The offset in the buffer at which the packet starts.
	 * @param replyTo The peer to which we need to send the packet
	 * @param pn The peerNode we are talking to. Cannot be null as we are the initiator.
	 */
	private void processJFKMessage2(byte[] payload,int inputOffset,PeerNode pn,Peer replyTo, boolean unknownInitiator, int setupType, int negType)
	{
		long t1=System.currentTimeMillis();
        int minPubkeyLength = minPubkeyLength(negType);
		int minSignatureLength = minSignatureLength(negType);
		if(logMINOR) Logger.minor(this, "Got a JFK(2) message, processing it - "+pn.getPeer());
		// FIXME: follow the spec and send IDr' ?
		int expectedLength = inputOffset + NONCE_SIZE*2 + minPubkeyLength + minSignatureLength + HASH_LENGTH;
		if(payload.length < expectedLength) {
			Logger.error(this, "Packet too short from "+pn.getPeer()+": "+payload.length+" after decryption in JFK(2), should be "+expectedLength);
			return;
		}

		byte[] nonceInitiator = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		byte[] nonceResponder = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;

		byte[] hisExponential = extractPublicKey(negType, payload, inputOffset);
		if (hisExponential == null) {
			Logger.error(this, "Cannot read public key from "+pn+": packet too short or invalid DER format");
			return;
		}
		// signatureLength can deal with overflow, delay payload size recheck
		inputOffset += hisExponential.length;

		int signatureOffset = inputOffset;
		int signatureLength = signatureLength(negType);
		if ((hisExponential.length+signatureLength) != (minPubkeyLength + minSignatureLength)) {
			// minimal packet size refined, recheck
			expectedLength +=
				+ hisExponential.length - minPubkeyLength
				+ signatureLength - minSignatureLength;
			if(payload.length < expectedLength) {
				Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(2), should be "+expectedLength);
				return;
			}
		}
		inputOffset += signatureLength;

		byte[] authenticator = Arrays.copyOfRange(payload, inputOffset, inputOffset + HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message2
		// Now simply transmit the corresponding message3
		Object message3 = null;
		synchronized (authenticatorCache) {
			message3 = authenticatorCache.get(new ByteArrayWrapper(authenticator));
		}
		if(message3 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) - "+pn.getPeer());
			sendAuthPacket(1, negType, 3, (byte[]) message3, pn, replyTo);
			return;
		}

		// sanity check
		byte[] myNi = null;
		synchronized (pn) {
			for(byte[] buf : pn.jfkNoncesSent) {
				if(Arrays.equals(nonceInitiator, buf))
					myNi = buf;
			}
		}
		// We don't except such a message;
		if(myNi == null) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "We received an unexpected JFK(2) message from "+pn.getPeer()+" (time since added: "+pn.timeSinceAddedOrRestarted()+" time last receive:"+pn.lastReceivedPacketTime()+')');
			}
			return;
		} else if(!Arrays.equals(myNi, nonceInitiator)) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "Ignoring old JFK(2) (different nonce to the one we sent - either a timing artefact or an attempt to change the nonce)");
			}
			return;
		}

		if(negType < 8) { // legacy DH
		    NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		    if(!DiffieHellman.checkDHExponentialValidity(this.getClass(), _hisExponential)) {
		        Logger.error(this, "We can't accept the exponential "+pn.getPeer()+" sent us!! REDFLAG: IT CAN'T HAPPEN UNLESS AGAINST AN ACTIVE ATTACKER!!");
		        return;
		    }
			// JFK protects us from weak key attacks on ECDH, so we don't need to check.
		}

		// Verify the DSA signature
		// At that point we don't know if it's "him"; let's check it out

		if(!VerifyPeerSignature(negType, pn,
					payload, signatureOffset, signatureLength,
					hisExponential,
					(negType >= 9 ? null :
					 pn.peerCryptoGroup.getP().toByteArray())))
		{
			Logger.error(this, "The signature verification has failed in JFK(2)!! "+pn.getPeer());
			return;
		}

		// At this point we know it's from the peer, so we can report a packet received.
		pn.receivedPacket(true, false);

		sendJFKMessage3(1, negType, 3, nonceInitiator, nonceResponder, hisExponential, authenticator, pn, replyTo, unknownInitiator, setupType);

		long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message2 timeout error:Processing packet for "+pn.getPeer());
		}
	}

	/*
	 * Initiator Method:Message3
	 * Process Message3
	 * Send the Initiator nonce,Responder nonce and DiffieHellman Exponential of the responder
	 * and initiator in the clear.(unVerifiedData)
	 * Send the authenticator which allows the responder to verify that a roundtrip occured
	 * Compute the signature of the unVerifiedData and encrypt it using a shared key
	 * which is derived from DHExponentials and the nonces; add a HMAC to protect it
	 *
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI*]
	 *
	 * * Noderef is sent whether or not unknownInitiator is true, however if it is, it will
	 * be a *full* noderef, otherwise it will exclude the pubkey etc.
	 *
	 * @param payload The buffer containing the decrypted auth packet.
	 * @param replyTo The peer to which we need to send the packet.
	 * @param pn The PeerNode we are talking to. CAN BE NULL in the case of anonymous initiator since we are the
	 * responder.
	 * @return byte Message3
	 */
	private void processJFKMessage3(byte[] payload, int inputOffset, PeerNode pn,Peer replyTo, boolean oldOpennetPeer, boolean unknownInitiator, int setupType, int negType)
	{
		final long t1 = System.currentTimeMillis();
		int minPubkeyLength = minPubkeyLength(negType);
		int minSignatureLength = minSignatureLength(negType);
		if(logMINOR) Logger.minor(this, "Got a JFK(3) message, processing it - "+pn);

		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }

		int expectedLength =
			inputOffset +
			NONCE_SIZE*2 + // Ni, Nr
			minPubkeyLength*2 + // g^i, g^r
			HASH_LENGTH + // authenticator
			HASH_LENGTH + // HMAC of the cyphertext
			(c.getBlockSize() >> 3) + // IV
			minSignatureLength + // it's at least a signature
			8 +	      // a bootid
			8 + // packet tracker ID
			1;	      // znoderefI* is at least 1 byte long

		if(payload.length < expectedLength) {
			Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(3), should be "+expectedLength);
			return;
		}

		// Ni
		byte[] nonceInitiator = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// Nr
		byte[] nonceResponder = Arrays.copyOfRange(payload, inputOffset, inputOffset+NONCE_SIZE);
		inputOffset += NONCE_SIZE;
		// g^i
		byte[] initiatorExponential = extractPublicKey(negType, payload, inputOffset);
		if(initiatorExponential == null) {
			Logger.error(this, "Cannot read initiator public key from "+pn+": packet too short or invalid DER format");
			return;
		}
		// extractPublicKey can deal with overflow, delay payload size recheck
		inputOffset += initiatorExponential.length;
		// g^r
		byte[] responderExponential = extractPublicKey(negType, payload, inputOffset);
		if(responderExponential == null) {
			Logger.error(this, "Cannot read responder public key from "+pn+": packet too short or invalid DER format");
			return;
		}
		if(initiatorExponential.length + responderExponential.length != minPubkeyLength*2) {
			expectedLength +=
				+ initiatorExponential.length - minPubkeyLength
				+ responderExponential.length - minPubkeyLength;
			if(payload.length < expectedLength) {
				Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(3), should be "+expectedLength);
				return;
			}
		}
		inputOffset += responderExponential.length;

		byte[] authenticator = Arrays.copyOfRange(payload, inputOffset, inputOffset+HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		// We *WANT* to check the hmac before we do the lookup on the hashmap
		// @see https://bugs.freenetproject.org/view.php?id=1604

		if(!Arrays.equals(macJFKAuthenticator(getTransientKey(), responderExponential, initiatorExponential, nonceResponder, nonceInitiator, replyTo.getAddress().getAddress()), authenticator)) {
			if(shouldLogErrorInHandshake(t1)) {
				Logger.normal(this, "The HMAC doesn't match; let's discard the packet (either we rekeyed or we are victim of forgery) - JFK3 - "+pn);
			}
			return;
		}
		// Check try to find the authenticator in the cache.
		// If authenticator is already present, indicates duplicate/replayed message3
		// Now simply transmit the corresponding message4
		Object message4 = null;
		synchronized (authenticatorCache) {
			message4 = authenticatorCache.get(new ByteArrayWrapper(authenticator));
		}
		if(message4 != null) {
			Logger.normal(this, "We replayed a message from the cache (shouldn't happen often) - "+pn);
			// We are replaying a JFK(4).
			// Therefore if it is anon-initiator it is encrypted with our setup key.
			if(unknownInitiator) {
				sendAnonAuthPacket(1,negType,3,setupType, (byte[]) message4, null, replyTo, crypto.anonSetupCipher);
			} else {
				sendAuthPacket(1, negType, 3, (byte[]) message4, pn, replyTo);
			}
			return;
		} else {
			if(logDEBUG) Logger.debug(this, "No message4 found for "+HexUtil.bytesToHex(authenticator)+" responderExponential "+Fields.hashCode(responderExponential)+" initiatorExponential "+Fields.hashCode(initiatorExponential)+" nonceResponder "+Fields.hashCode(nonceResponder)+" nonceInitiator "+Fields.hashCode(nonceInitiator)+" address "+HexUtil.bytesToHex(replyTo.getAddress().getAddress()));
		}

		byte[] hmac = Arrays.copyOfRange(payload, inputOffset, inputOffset+HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		byte[] computedExponential;
		if(negType < 8) { // Legacy DH
			NativeBigInteger _hisExponential = new NativeBigInteger(1, initiatorExponential);
			NativeBigInteger _ourExponential = new NativeBigInteger(1, responderExponential);

			DiffieHellmanLightContext ctx = findContextByExponential(_ourExponential);
			if(ctx == null) {
				Logger.error(this, "WTF? the HMAC verified but we don't know about that exponential! SHOULDN'T HAPPEN! - JFK3 - "+pn);
				// Possible this is a replay or severely delayed? We don't keep every exponential we ever use.
				return;
			}
			computedExponential = ctx.getHMACKey(_hisExponential);
        } else {
            ECPublicKey initiatorKey = ECDH.getPublicKey(initiatorExponential, ecdhCurveToUse);
            ECPublicKey responderKey = ECDH.getPublicKey(responderExponential, ecdhCurveToUse);
            ECDHLightContext ctx = findECDHContextByPubKey(responderKey);
            if (ctx == null) {
                Logger.error(this, "WTF? the HMAC verified but we don't know about that exponential! SHOULDN'T HAPPEN! - JFK3 - "+pn);
                // Possible this is a replay or severely delayed? We don't keep
                // every exponential we ever use.
                return;
            }
            computedExponential = ctx.getHMACKey(initiatorKey);
        }
		if(logDEBUG) Logger.debug(this, "The shared Master secret is : "+HexUtil.bytesToHex(computedExponential) +" for " + pn);
		
		HMAC mac = HMAC.getMacWithSHA256();
		mac.init(computedExponential);
		computedExponential = null;
		byte[] toHash = prepareJFKtoHash(nonceInitiator, nonceResponder);
		/* 0 is the outgoing key for the initiator, 7 for the responder */
		byte[] outgoingKey = computeJFKSharedKey(mac, toHash, 7);
		byte[] incommingKey = computeJFKSharedKey(mac, toHash, 0);
		byte[] Ke = computeJFKSharedKey(mac, toHash, 1);
		byte[] Ka = computeJFKSharedKey(mac, toHash, 2);

		byte[] hmacKey = computeJFKSharedKey(mac, toHash, 3);
		byte[] ivKey = computeJFKSharedKey(mac, toHash, 4);
		byte[] ivNonce = computeJFKSharedKey(mac, toHash, 5);

		/* Bytes  1-4:  Initial sequence number for the initiator
		 * Bytes  5-8:  Initial sequence number for the responder
		 * Bytes  9-12: Initial message id for the initiator
		 * Bytes 13-16: Initial message id for the responder
		 * Note that we are the responder */
		byte[] sharedData = computeJFKSharedKey(mac, toHash, 6);
		HMAC.returnMacWithSHA256(mac); mac = null;
		toHash = null;

		int theirInitialSeqNum = ((sharedData[0] & 0xFF) << 24)
				| ((sharedData[1] & 0xFF) << 16)
				| ((sharedData[2] & 0xFF) << 8)
				| (sharedData[3] & 0xFF);
		int ourInitialSeqNum = ((sharedData[4] & 0xFF) << 24)
				| ((sharedData[5] & 0xFF) << 16)
				| ((sharedData[6] & 0xFF) << 8)
				| (sharedData[7] & 0xFF);
		int theirInitialMsgID, ourInitialMsgID;
		if(negType >= 7) {
			theirInitialMsgID =
				unknownInitiator ? getInitialMessageID(crypto.myIdentity) :
					getInitialMessageID(pn.identity, crypto.myIdentity);
			ourInitialMsgID =
				unknownInitiator ? getInitialMessageID(crypto.myIdentity) :
					getInitialMessageID(crypto.myIdentity, pn.identity);
		} else {
			theirInitialMsgID= ((sharedData[8] & 0xFF) << 24)
				| ((sharedData[9] & 0xFF) << 16)
				| ((sharedData[10] & 0xFF) << 8)
				| (sharedData[11] & 0xFF);
			ourInitialMsgID= ((sharedData[12] & 0xFF) << 24)
				| ((sharedData[13] & 0xFF) << 16)
				| ((sharedData[14] & 0xFF) << 8)
				| (sharedData[15] & 0xFF);
		}
		if(logMINOR)
			Logger.minor(this, "Their initial message ID: "+theirInitialMsgID+" ours "+ourInitialMsgID);

		c.initialize(Ke);
		int ivLength = PCFBMode.lengthIV(c);
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("I"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = Arrays.copyOf(JFK_PREFIX_INITIATOR, JFK_PREFIX_INITIATOR.length + payload.length - inputOffset);
		decypheredPayloadOffset += JFK_PREFIX_INITIATOR.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		if(!HMAC.verifyWithSHA256(Ka, decypheredPayload, hmac)) {
			Logger.error(this, "The inner-HMAC doesn't match; let's discard the packet JFK(3) - "+pn);
			return;
		}

		final PCFBMode pk = PCFBMode.create(c, decypheredPayload, decypheredPayloadOffset);
		// Get the IV
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length-decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * Node Data (starting with BootID)
		 */
		int signatureOffset = decypheredPayloadOffset;
		int signatureLength = signatureLength(negType);
		if (signatureLength != minSignatureLength) {
			// minimal packet size refined, recheck
			expectedLength += signatureLength-minSignatureLength;
			if(payload.length < expectedLength) {
				Logger.error(this, "Packet too short from "+pn+": "+payload.length+" after decryption in JFK(3), should be "+expectedLength);
				return;
			}
		}
		decypheredPayloadOffset += signatureLength;

		byte[] data = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length);
		int ptr = 0;
		long trackerID;
		trackerID = Fields.bytesToLong(data, ptr);
		if(trackerID < 0) trackerID = -1;
		ptr += 8;
		long bootID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		byte[] hisRef = Arrays.copyOfRange(data, ptr, data.length);

		// construct the peernode
		if(unknownInitiator) {
			pn = getPeerNodeFromUnknownInitiator(hisRef, setupType, pn, replyTo);
		}
		if(pn == null) {
			if(unknownInitiator) {
				// Reject
				Logger.normal(this, "Rejecting... unable to construct PeerNode");
			} else {
				Logger.error(this, "PeerNode is null and unknownInitiator is false!");
			}
			return;
		}

		// verify the signature
		if(!VerifyPeerSignature(negType, pn,
					decypheredPayload, signatureOffset, signatureLength,
					nonceInitiator, nonceResponder,
					initiatorExponential, responderExponential,
					crypto.myIdentity, data)) {
			Logger.error(this, "The signature verification has failed!! JFK(3) - "+pn.getPeer());
			return;
		}

		// At this point we know it's from the peer, so we can report a packet received.
		pn.receivedPacket(true, false);

		BlockCipher outgoingCipher = null;
		BlockCipher incommingCipher = null;
		BlockCipher ivCipher = null;
		try {
			outgoingCipher = new Rijndael(256, 256);
			incommingCipher = new Rijndael(256, 256);
			ivCipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new RuntimeException(e);
		}
		outgoingCipher.initialize(outgoingKey);
		incommingCipher.initialize(incommingKey);
		ivCipher.initialize(ivKey);

		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return;
			}
			/* When an old-opennet-peer connects, add it at the top of the LRU, so that it isn't
			 * immediately dropped when there is no droppable peer to drop. If it was dropped
			 * from the bottom of the LRU list, we would not have added it to the LRU; so it was
			 * somewhere in the middle. */
			if(!opennet.wantPeer(pn, false, false, true, ConnectionType.RECONNECT)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
				opennet.purgeOldOpennetPeer(pn);
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		if((!dontWant) && !crypto.allowConnection(pn, replyTo.getFreenetAddress())) {
			if(pn instanceof DarknetPeerNode) {
				Logger.error(this, "Dropping peer "+pn+" because don't want connection due to others on the same IP address!");
				System.out.println("Disconnecting permanently from your friend \""+((DarknetPeerNode)pn).getName()+"\" because other peers are using the same IP address!");
			}
			Logger.normal(this, "Rejecting connection because already have something with the same IP");
			dontWant = true;
		}

		long newTrackerID = pn.completedHandshake(
				bootID, hisRef, 0, hisRef.length, outgoingCipher, outgoingKey, incommingCipher,
				incommingKey, replyTo, true, negType, trackerID, false, false, hmacKey, ivCipher,
				ivNonce, ourInitialSeqNum, theirInitialSeqNum, ourInitialMsgID, theirInitialMsgID);

		if(newTrackerID > 0) {

			// Send reply
			sendJFKMessage4(1, negType, 3, nonceInitiator, nonceResponder,initiatorExponential, responderExponential,
					c, Ke, Ka, authenticator, hisRef, pn, replyTo, unknownInitiator, setupType, newTrackerID, newTrackerID == trackerID);

			if(dontWant) {
				node.peers.disconnectAndRemove(pn, true, true, true); // Let it connect then tell it to remove it.
			} else {
				pn.maybeSendInitialMessages();
			}
		} else {
			Logger.error(this, "Handshake failure! with "+pn.getPeer());
			// Don't send the JFK(4). We have not successfully connected.
		}

		final long t2=System.currentTimeMillis();
		if((t2-t1)>500) {
			Logger.error(this,"Message3 Processing packet for "+pn.getPeer()+" took "+TimeUtil.formatTime(t2-t1, 3, true));
		}
	}

	private PeerNode getPeerNodeFromUnknownInitiator(byte[] hisRef, int setupType, PeerNode pn, Peer from) {
		if(setupType == SETUP_OPENNET_SEEDNODE) {
			OpennetManager om = node.getOpennet();
			if(om == null) {
				Logger.error(this, "Opennet disabled, ignoring seednode connect attempt");
				// FIXME Send some sort of explicit rejection message.
				return null;
			}
			SimpleFieldSet ref = OpennetManager.validateNoderef(hisRef, 0, hisRef.length, null, true);
			if(ref == null) {
				Logger.error(this, "Invalid noderef");
				// FIXME Send some sort of explicit rejection message.
				return null;
			}
			PeerNode seed;
			try {
				seed = new SeedClientPeerNode(ref, node, crypto, node.peers, false, true, crypto.packetMangler);
				// Don't tell tracker yet as we don't have the address yet.
			} catch (FSParseException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			} catch (PeerParseException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			} catch (ReferenceSignatureVerificationException e) {
				Logger.error(this, "Invalid seed client noderef: "+e+" from "+from, e);
				return null;
			}
			if(seed.equals(pn)) {
				Logger.normal(this, "Already connected to seednode");
				return pn;
			}
			node.peers.addPeer(seed);
			return seed;
		} else {
			Logger.error(this, "Unknown setup type");
			return null;
		}
	}

	/*
	 * Responder Method:Message4
	 * Process Message4
	 *
	 * Format:
	 * HMAC{Ka}[cyphertext]
	 * IV + E{Ke}[S{R}[Ni, Nr, g^i, g^r, IDi, bootID, znoderefR, znoderefI], bootID, znoderefR]
	 *
	 * @param payload The decrypted auth packet.
	 * @param pn The PeerNode we are talking to. Cannot be null as we are the initiator.
	 * @param replyTo The Peer we are replying to.
	 */
	private boolean processJFKMessage4(byte[] payload, int inputOffset, PeerNode pn, Peer replyTo, boolean oldOpennetPeer, boolean unknownInitiator, int setupType, int negType)
	{
		final long t1 = System.currentTimeMillis();
		if(logMINOR) Logger.minor(this, "Got a JFK(4) message, processing it - "+pn.getPeer());
		if(pn.jfkMyRef == null) {
			String error = "Got a JFK(4) message but no pn.jfkMyRef for "+pn;
			if(node.getUptime() < 60*1000) {
				Logger.minor(this, error);
			} else {
				Logger.error(this, error);
			}
		}
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }

		int minSignatureLength = minSignatureLength(negType);
		int expectedLength =
			inputOffset +
			HASH_LENGTH + // HMAC of the cyphertext
			(c.getBlockSize() >> 3) + // IV
			minSignatureLength + // the signature
			9 + // ID of packet tracker, plus boolean byte
			8+ // bootID
			1; // znoderefR

		if(payload.length < expectedLength) {
			Logger.error(this, "Packet too short from "+pn.getPeer()+": "+payload.length+" after decryption in JFK(4), should be "+expectedLength);
			return false;
		}
		byte[] jfkBuffer = pn.getJFKBuffer();
		if(jfkBuffer == null) {
			Logger.normal(this, "We have already handled this message... might be a replay or a bug - "+pn);
			return false;
		}

		byte[] hmac = Arrays.copyOfRange(payload, inputOffset, inputOffset+HASH_LENGTH);
		inputOffset += HASH_LENGTH;

		c.initialize(pn.jfkKe);
		int ivLength = PCFBMode.lengthIV(c);
		int decypheredPayloadOffset = 0;
		// We compute the HMAC of ("R"+cyphertext) : the cyphertext includes the IV!
		byte[] decypheredPayload = Arrays.copyOf(JFK_PREFIX_RESPONDER, JFK_PREFIX_RESPONDER.length + payload.length - inputOffset);
		decypheredPayloadOffset += JFK_PREFIX_RESPONDER.length;
		System.arraycopy(payload, inputOffset, decypheredPayload, decypheredPayloadOffset, payload.length-inputOffset);
		if(!HMAC.verifyWithSHA256(pn.jfkKa, decypheredPayload, hmac)) {
			Logger.normal(this, "The digest-HMAC doesn't match; let's discard the packet - "+pn.getPeer());
			return false;
		}

		// Try to find the HMAC in the cache:
		// If it is already present it indicates duplicate/replayed message4 and we can discard
		// If it's not, we can add it with a timestamp
		byte[] message4Timestamp = null;
		synchronized (authenticatorCache) {
			ByteArrayWrapper hmacBAW = new ByteArrayWrapper(hmac);
			message4Timestamp = authenticatorCache.get(hmacBAW);
			if(message4Timestamp == null) { // normal behaviour
				authenticatorCache.put(hmacBAW, Fields.longToBytes(t1));
			}
		}
		if(message4Timestamp != null) {
			Logger.normal(this, "We got a replayed message4 (first handled at "+TimeUtil.formatTime(t1-Fields.bytesToLong(message4Timestamp))+") from - "+pn);
			return true;
		}

		// Get the IV
		final PCFBMode pk = PCFBMode.create(c, decypheredPayload, decypheredPayloadOffset);
		decypheredPayloadOffset += ivLength;
		// Decrypt the payload
		pk.blockDecipher(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length - decypheredPayloadOffset);
		/*
		 * DecipheredData Format:
		 * Signature-r,s
		 * bootID, znoderef
		 */
		int signatureLength = signatureLength(negType);
		int signatureOffset = decypheredPayloadOffset;
		if (signatureLength != minSignatureLength) {
			// minimal packet size refined, recheck
			expectedLength += signatureLength-minSignatureLength;
			if(payload.length < expectedLength) {
				Logger.error(this, "Packet too short from "+pn.getPeer()+": "+payload.length+" after decryption in JFK(4), should be "+expectedLength);
				return false;
			}
		}
		decypheredPayloadOffset += signatureLength;

		byte[] data = Arrays.copyOfRange(decypheredPayload, decypheredPayloadOffset, decypheredPayload.length);
		int ptr = 0;
		long trackerID;
		boolean reusedTracker;
		trackerID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		reusedTracker = data[ptr++] != 0;
		long bootID = Fields.bytesToLong(data, ptr);
		ptr += 8;
		byte[] hisRef = Arrays.copyOfRange(data, ptr, data.length);

		// verify the signature
		if(!VerifyPeerSignature(negType, pn,
					decypheredPayload, signatureOffset, signatureLength,
					jfkBuffer, //< nonceInitiator, nonceResponder, initiatorExponential, responderExponential
					crypto.getIdentity(unknownInitiator), data, pn.jfkMyRef)) {
			String error = "The signature verification has failed!! JFK(4) -"+pn.getPeer()+" hisRef "+hisRef.length+" hash "+Fields.hashCode(hisRef)+" myRef "+pn.jfkMyRef.length+" hash "+Fields.hashCode(pn.jfkMyRef)+" boot ID "+bootID;
			Logger.error(this, error);
			return true;
		}

		// Received a packet
		pn.receivedPacket(true, false);

		// Promote if necessary
		boolean dontWant = false;
		if(oldOpennetPeer) {
			OpennetManager opennet = node.getOpennet();
			if(opennet == null) {
				Logger.normal(this, "Dumping incoming old-opennet peer as opennet just turned off: "+pn+".");
				return true;
			}
			/* When an old-opennet-peer connects, add it at the top of the LRU, so that it isn't
			 * immediately dropped when there is no droppable peer to drop. If it was dropped
			 * from the bottom of the LRU list, we would not have added it to the LRU; so it was
			 * somewhere in the middle. */
			if(!opennet.wantPeer(pn, false, false, true, ConnectionType.RECONNECT)) {
				Logger.normal(this, "No longer want peer "+pn+" - dumping it after connecting");
				dontWant = true;
				opennet.purgeOldOpennetPeer(pn);
			}
			// wantPeer will call node.peers.addPeer(), we don't have to.
		}
		if((!dontWant) && !crypto.allowConnection(pn, replyTo.getFreenetAddress())) {
			Logger.normal(this, "Rejecting connection because already have something with the same IP");
			dontWant = true;
		}

		// We change the key
		BlockCipher ivCipher = null;
		BlockCipher outgoingCipher = null;
		BlockCipher incommingCipher = null;
		try {
			ivCipher = new Rijndael(256, 256);
			outgoingCipher = new Rijndael(256, 256);
			incommingCipher = new Rijndael(256, 256);
		} catch (UnsupportedCipherException e) {
			throw new RuntimeException(e);
		}

		outgoingCipher.initialize(pn.outgoingKey);
		incommingCipher.initialize(pn.incommingKey);
		ivCipher.initialize(pn.ivKey);

		long newTrackerID = pn.completedHandshake(
				bootID, hisRef, 0, hisRef.length, outgoingCipher, pn.outgoingKey, incommingCipher,
				pn.incommingKey, replyTo, false, negType, trackerID, true, reusedTracker, pn.hmacKey,
				ivCipher, pn.ivNonce, pn.ourInitialSeqNum, pn.theirInitialSeqNum, pn.ourInitialMsgID,
				pn.theirInitialMsgID);
		if(newTrackerID >= 0) {
			if(dontWant) {
				node.peers.disconnectAndRemove(pn, true, true, true);
			} else {
				pn.maybeSendInitialMessages();
			}
		} else {
			Logger.error(this, "Handshake failed!");
		}

		// cleanup
		// FIXME: maybe we should copy zeros/garbage into it before leaving it to the GC
		pn.setJFKBuffer(null);
		pn.jfkKa = null;
		pn.jfkKe = null;
		pn.outgoingKey = null;
		pn.incommingKey = null;
		pn.hmacKey = null;
		pn.ivKey = null;
		pn.ivNonce = null;
		pn.ourInitialSeqNum = 0;
		pn.theirInitialSeqNum = 0;
		pn.ourInitialMsgID = 0;
		pn.theirInitialMsgID = 0;
		// We want to clear it here so that new handshake requests
		// will be sent with a different DH pair
		pn.setKeyAgreementSchemeContext(null);
		synchronized (pn) {
			// FIXME TRUE MULTI-HOMING: winner-takes-all, kill all other connection attempts since we can't deal with multiple active connections
			// Also avoids leaking
			pn.jfkNoncesSent.clear();
		}

		final long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message4 timeout error:Processing packet from "+pn.getPeer());
		return true;
	}

	/*
	 * Format:
	 * Ni, Nr, g^i, g^r
	 * Authenticator - HMAC{Hkr}[g^r, g^i, Nr, Ni, IPi]
	 * HMAC{Ka}(cyphertext)
	 * IV + E{KE}[S{i}[Ni,Nr,g^i,g^r,idR, bootID, znoderefI], bootID, znoderefI]
	 *
	 * @param pn The PeerNode to encrypt the message for. Cannot be null as we are the initiator.
	 * @param replyTo The Peer to send the packet to.
	 */

	private void sendJFKMessage3(int version,final int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] hisExponential, byte[] authenticator, final PeerNode pn, final Peer replyTo, final boolean unknownInitiator, final int setupType)
	{
		if(logMINOR) Logger.minor(this, "Sending a JFK(3) message to "+pn.getPeer());
		long t1=System.currentTimeMillis();
		BlockCipher c = null;
		try { c = new Rijndael(256, 256); } catch (UnsupportedCipherException e) { throw new RuntimeException(e); }
		KeyAgreementSchemeContext ctx = pn.getKeyAgreementSchemeContext();
		if(ctx == null) return;
		byte[] ourExponential = ctx.getPublicKeyNetworkFormat();
		pn.jfkMyRef = unknownInitiator ? crypto.myCompressedHeavySetupRef() : crypto.myCompressedSetupRef();
		byte[] data = new byte[8 + 8 + pn.jfkMyRef.length];
		int ptr = 0;
		long trackerID;
		trackerID = pn.getReusableTrackerID();
		System.arraycopy(Fields.longToBytes(trackerID), 0, data, ptr, 8);
		ptr += 8;
		if(logMINOR) Logger.minor(this, "Sending tracker ID "+trackerID+" in JFK(3)");
		System.arraycopy(Fields.longToBytes(pn.getOutgoingBootID()), 0, data, ptr, 8);
		ptr += 8;
		System.arraycopy(pn.jfkMyRef, 0, data, ptr, pn.jfkMyRef.length);

		/*
		 * Digital Signature of the message with the private key belonging to the initiator/responder
		 * It is assumed to be non-message recovering
		 */
		// save parameters so that we can verify message4
		byte[] toSign = new byte [nonceInitiator.length + nonceResponder.length + ourExponential.length + hisExponential.length];
		int x = 0;
		System.arraycopy(nonceInitiator, 0, toSign, x, nonceInitiator.length);
		x += nonceInitiator.length;
		System.arraycopy(nonceResponder, 0, toSign, x, nonceResponder.length);
		x += nonceResponder.length;
		System.arraycopy(ourExponential, 0, toSign, x, ourExponential.length);
		x += ourExponential.length;
		System.arraycopy(hisExponential, 0, toSign, x, hisExponential.length);
		x += hisExponential.length;
		// assert(x == toSign.length);
		pn.setJFKBuffer(toSign);
		byte[] localSignature = Sign(negType,
				toSign, //< nonceInitiator, nonceResponder, ourExponential, hisExponential
				pn.identity, data);

		final byte[] message3 = new byte[NONCE_SIZE*2 + // nI, nR
		                           ourExponential.length +
								   hisExponential.length + // g^i, g^r
		                           HASH_LENGTH + // authenticator
		                           HASH_LENGTH + // HMAC(cyphertext)
		                           (c.getBlockSize() >> 3) + // IV
		                           localSignature.length + // Signature (R,S)
		                           data.length]; // The bootid+noderef
		int offset = 0;
		// Ni
		System.arraycopy(nonceInitiator, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// Nr
		System.arraycopy(nonceResponder, 0, message3, offset, NONCE_SIZE);
		offset += NONCE_SIZE;
		// g^i
		System.arraycopy(ourExponential, 0,message3, offset, ourExponential.length);
		offset += ourExponential.length;
		// g^r
		System.arraycopy(hisExponential, 0,message3, offset, hisExponential.length);
		offset += hisExponential.length;

		// Authenticator
		System.arraycopy(authenticator, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;

		byte[] computedExponential;
		if (negType < 8 ) { // Legacy DH
		    NativeBigInteger _hisExponential = new NativeBigInteger(1,hisExponential);
		    computedExponential= ((DiffieHellmanLightContext)ctx).getHMACKey(_hisExponential);
		}else {
		    computedExponential = ((ECDHLightContext)ctx).getHMACKey(ECDH.getPublicKey(hisExponential, ecdhCurveToUse));
		}
		if(logDEBUG) Logger.debug(this, "The shared Master secret is : "+HexUtil.bytesToHex(computedExponential)+ " for " + pn);
		HMAC mac = HMAC.getMacWithSHA256();
		mac.init(computedExponential);
		computedExponential = null;
		byte[] toHash = prepareJFKtoHash(nonceInitiator, nonceResponder);
		/* 0 is the outgoing key for the initiator, 7 for the responder */
		pn.outgoingKey = computeJFKSharedKey(mac, toHash, 0);
		pn.incommingKey = computeJFKSharedKey(mac, toHash, 7);
		pn.jfkKe = computeJFKSharedKey(mac, toHash, 1);
		pn.jfkKa = computeJFKSharedKey(mac, toHash, 2);

		pn.hmacKey = computeJFKSharedKey(mac, toHash, 3);
		pn.ivKey = computeJFKSharedKey(mac, toHash, 4);
		pn.ivNonce = computeJFKSharedKey(mac, toHash, 5);

		/* Bytes  1-4:  Initial sequence number for the initiator
		 * Bytes  5-8:  Initial sequence number for the responder
		 * Bytes  9-12: Initial message id for the initiator
		 * Bytes 13-16: Initial message id for the responder
		 * Note that we are the initiator */
		byte[] sharedData = computeJFKSharedKey(mac, toHash, 6);
		HMAC.returnMacWithSHA256(mac); mac = null;
		toHash = null;
		pn.ourInitialSeqNum = ((sharedData[0] & 0xFF) << 24)
				| ((sharedData[1] & 0xFF) << 16)
				| ((sharedData[2] & 0xFF) << 8)
				| (sharedData[3] & 0xFF);
		pn.theirInitialSeqNum = ((sharedData[4] & 0xFF) << 24)
				| ((sharedData[5] & 0xFF) << 16)
				| ((sharedData[6] & 0xFF) << 8)
				| (sharedData[7] & 0xFF);
		if(negType >= 7) {
			pn.theirInitialMsgID =
				unknownInitiator ? getInitialMessageID(pn.identity) :
					getInitialMessageID(pn.identity, crypto.myIdentity);
			pn.ourInitialMsgID =
				unknownInitiator ? getInitialMessageID(pn.identity) :
					getInitialMessageID(crypto.myIdentity, pn.identity);
		} else {
			pn.ourInitialMsgID= ((sharedData[8] & 0xFF) << 24)
				| ((sharedData[9] & 0xFF) << 16)
				| ((sharedData[10] & 0xFF) << 8)
				| (sharedData[11] & 0xFF);
			pn.theirInitialMsgID= ((sharedData[12] & 0xFF) << 24)
				| ((sharedData[13] & 0xFF) << 16)
				| ((sharedData[14] & 0xFF) << 8)
				| (sharedData[15] & 0xFF);
		}
			
		if(logMINOR)
			Logger.minor(this, "Their initial message ID: "+pn.theirInitialMsgID+" ours "+pn.ourInitialMsgID);


		c.initialize(pn.jfkKe);
		int ivLength = PCFBMode.lengthIV(c);
		byte[] iv = new byte[ivLength];
		node.random.nextBytes(iv);
		PCFBMode pcfb = PCFBMode.create(c, iv);
		int cleartextOffset = 0;
		byte[] cleartext = new byte[JFK_PREFIX_INITIATOR.length + ivLength + localSignature.length + data.length];
		System.arraycopy(JFK_PREFIX_INITIATOR, 0, cleartext, cleartextOffset, JFK_PREFIX_INITIATOR.length);
		cleartextOffset += JFK_PREFIX_INITIATOR.length;
		System.arraycopy(iv, 0, cleartext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(localSignature, 0, cleartext, cleartextOffset, localSignature.length);
		cleartextOffset += localSignature.length;
		System.arraycopy(data, 0, cleartext, cleartextOffset, data.length);
		cleartextOffset += data.length;

		int cleartextToEncypherOffset = JFK_PREFIX_INITIATOR.length + ivLength;
		pcfb.blockEncipher(cleartext, cleartextToEncypherOffset, cleartext.length-cleartextToEncypherOffset);

		// We compute the HMAC of (prefix + cyphertext) Includes the IV!
		byte[] hmac = HMAC.macWithSHA256(pn.jfkKa, cleartext, HASH_LENGTH);

		// copy stuffs back to the message
		System.arraycopy(hmac, 0, message3, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message3, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cleartext, cleartextToEncypherOffset, message3, offset, cleartext.length-cleartextToEncypherOffset);

		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(new ByteArrayWrapper(authenticator),message3);
		}
		final long timeSent = System.currentTimeMillis();
		if(unknownInitiator) {
			sendAnonAuthPacket(1, negType, 2, setupType, message3, pn, replyTo, pn.anonymousInitiatorSetupCipher);
		} else {
			sendAuthPacket(1, negType, 2, message3, pn, replyTo);
		}

		/* Re-send the packet after 5sec if we don't get any reply */
		node.getTicker().queueTimedJob(new Runnable() {
			@Override
			public void run() {
				if(pn.timeLastConnectionCompleted() < timeSent) {
					if(logMINOR) Logger.minor(this, "Resending JFK(3) to "+pn+" for "+node.getDarknetPortNumber());
					if(unknownInitiator) {
						sendAnonAuthPacket(1, negType, 2, setupType, message3, pn, replyTo, pn.anonymousInitiatorSetupCipher);
					} else {
						sendAuthPacket(1, negType, 2, message3, pn, replyTo);
					}
				}
			}
		}, 5*1000);
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message3 timeout error:Sending packet for "+pn.getPeer());
	}

	private int getInitialMessageID(byte[] identity) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(identity);
		// Similar to JFK keygen, should be safe enough.
		try {
			md.update("INITIAL0".getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		byte[] hashed = md.digest();
		SHA256.returnMessageDigest(md);
		return Fields.bytesToInt(hashed, 0);
	}

	private int getInitialMessageID(byte[] identity, byte[] otherIdentity) {
		MessageDigest md = SHA256.getMessageDigest();
		md.update(identity);
		md.update(otherIdentity);
		// Similar to JFK keygen, should be safe enough.
		try {
			md.update("INITIAL1".getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
		byte[] hashed = md.digest();
		SHA256.returnMessageDigest(md);
		return Fields.bytesToInt(hashed, 0);
	}

	/*
	 * Format:
	 * HMAC{Ka}(cyphertext)
	 * IV, E{Ke}[S{R}[Ni,Nr,g^i,g^r,idI, bootID, znoderefR, znoderefI],bootID,znoderefR]
	 *
	 * @param replyTo The Peer we are replying to.
	 * @param pn The PeerNode to encrypt the auth packet to. Cannot be null, because even in anonymous initiator,
	 * we will have created one before calling this method.
	 */
	private void sendJFKMessage4(int version,int negType,int phase,byte[] nonceInitiator,byte[] nonceResponder,byte[] initiatorExponential,byte[] responderExponential, BlockCipher c, byte[] Ke, byte[] Ka, byte[] authenticator, byte[] hisRef, PeerNode pn, Peer replyTo, boolean unknownInitiator, int setupType, long newTrackerID, boolean sameAsOldTrackerID)
	{
		if(logMINOR)
			Logger.minor(this, "Sending a JFK(4) message to "+pn.getPeer());
		long t1=System.currentTimeMillis();

		byte[] myRef = crypto.myCompressedSetupRef();
		byte[] data = new byte[9 + 8 + myRef.length + hisRef.length];
		int ptr = 0;
		System.arraycopy(Fields.longToBytes(newTrackerID), 0, data, ptr, 8);
		ptr += 8;
		data[ptr++] = (byte) (sameAsOldTrackerID ? 1 : 0);

		System.arraycopy(Fields.longToBytes(pn.getOutgoingBootID()), 0, data, ptr, 8);
		ptr += 8;
		System.arraycopy(myRef, 0, data, ptr, myRef.length);
		ptr += myRef.length;
		System.arraycopy(hisRef, 0, data, ptr, hisRef.length);

		/*
		int params_length = nonceInitiator.length + nonceResponder.length + initiatorExponential.length + responderExponential.length + pn.identity.length + data.length;
		if(logMINOR)
			Logger.minor(this, "Message hash: "+HexUtil.bytesToHex(messageHash)+" length "+params_length+" myRef: "+myRef.length+" hash "+Fields.hashCode(myRef)+" hisRef: "+hisRef.length+" hash "+Fields.hashCode(hisRef)+" boot ID "+node.bootID);
		*/
		byte[] localSignature = Sign(negType,
				nonceInitiator, nonceResponder,
				initiatorExponential, responderExponential,
				pn.identity, data);

		int ivLength = PCFBMode.lengthIV(c);
		byte[] iv=new byte[ivLength];
		node.random.nextBytes(iv);
		PCFBMode pk=PCFBMode.create(c, iv);
		// Don't include the last bit
		int dataLength = data.length - hisRef.length;
		byte[] cyphertext = new byte[JFK_PREFIX_RESPONDER.length + ivLength + localSignature.length +
		                             dataLength];
		int cleartextOffset = 0;
		System.arraycopy(JFK_PREFIX_RESPONDER, 0, cyphertext, cleartextOffset, JFK_PREFIX_RESPONDER.length);
		cleartextOffset += JFK_PREFIX_RESPONDER.length;
		System.arraycopy(iv, 0, cyphertext, cleartextOffset, ivLength);
		cleartextOffset += ivLength;
		System.arraycopy(localSignature, 0, cyphertext, cleartextOffset, localSignature.length);
		cleartextOffset += localSignature.length;
		System.arraycopy(data, 0, cyphertext, cleartextOffset, dataLength);
		cleartextOffset += dataLength;
		// Now encrypt the cleartext[Signature]
		int cleartextToEncypherOffset = JFK_PREFIX_RESPONDER.length + ivLength;
		pk.blockEncipher(cyphertext, cleartextToEncypherOffset, cyphertext.length - cleartextToEncypherOffset);

		// We compute the HMAC of (prefix + iv + signature)
		byte[] hmac = HMAC.macWithSHA256(Ka, cyphertext, HASH_LENGTH);

		// Message4 = hmac + IV + encryptedSignature
		byte[] message4 = new byte[HASH_LENGTH + ivLength + (cyphertext.length - cleartextToEncypherOffset)];
		int offset = 0;
		System.arraycopy(hmac, 0, message4, offset, HASH_LENGTH);
		offset += HASH_LENGTH;
		System.arraycopy(iv, 0, message4, offset, ivLength);
		offset += ivLength;
		System.arraycopy(cyphertext, cleartextToEncypherOffset, message4, offset, cyphertext.length - cleartextToEncypherOffset);

		// cache the message
		synchronized (authenticatorCache) {
			if(!maybeResetTransientKey())
				authenticatorCache.put(new ByteArrayWrapper(authenticator), message4);
			if(logDEBUG) Logger.debug(this, "Storing JFK(4) for "+HexUtil.bytesToHex(authenticator));
		}

		if(unknownInitiator) {
			sendAnonAuthPacket(1, negType, 3, setupType, message4, pn, replyTo, crypto.anonSetupCipher);
		} else {
			sendAuthPacket(1, negType, 3, message4, pn, replyTo);
		}
		long t2=System.currentTimeMillis();
		if((t2-t1)>500)
			Logger.error(this,"Message4 timeout error:Sending packet for "+pn.getPeer());
	}

	/**
	 * Send an auth packet.
	 */
	private void sendAuthPacket(int version, int negType, int phase, byte[] data, PeerNode pn, Peer replyTo) {
		if(pn == null) throw new IllegalArgumentException("pn shouldn't be null here!");
		byte[] output = new byte[data.length+3];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		System.arraycopy(data, 0, output, 3, data.length);
		if(logMINOR) {
			long now = System.currentTimeMillis();
			String delta = "never";
			long last = pn.lastSentPacketTime();
			delta = TimeUtil.formatTime(now - last, 2, true) + " ago";
			Logger.minor(this, "Sending auth packet for "+ String.valueOf(pn.getPeer())+" (phase="+phase+", ver="+version+", nt="+negType+") (last packet sent "+delta+") to "+replyTo+" data.length="+data.length+" to "+replyTo);
		}
		sendAuthPacket(output, pn.outgoingSetupCipher, pn, replyTo, false);
	}

	/**
	 * @param version
	 * @param negType
	 * @param phase
	 * @param setupType
	 * @param data
	 * @param pn May be null. If not null, used for details such as anti-firewall hacks.
	 * @param replyTo
	 * @param cipher
	 */
	private void sendAnonAuthPacket(int version, int negType, int phase, int setupType, byte[] data, PeerNode pn, Peer replyTo, BlockCipher cipher) {
		byte[] output = new byte[data.length+4];
		output[0] = (byte) version;
		output[1] = (byte) negType;
		output[2] = (byte) phase;
		output[3] = (byte) setupType;
		System.arraycopy(data, 0, output, 4, data.length);
		if(logMINOR) Logger.minor(this, "Sending anon auth packet (phase="+phase+", ver="+version+", nt="+negType+", setup="+setupType+") data.length="+data.length);
		sendAuthPacket(output, cipher, pn, replyTo, true);
	}

	/**
	 * Send an auth packet (we have constructed the payload, now hash it, pad it, encrypt it).
	 */
	private void sendAuthPacket(byte[] output, BlockCipher cipher, PeerNode pn, Peer replyTo, boolean anonAuth) {
		int length = output.length;
		if(length > sock.getMaxPacketSize()) {
			throw new IllegalStateException("Cannot send auth packet: too long: "+length);
		}
		byte[] iv = new byte[PCFBMode.lengthIV(cipher)];
		node.random.nextBytes(iv);
		byte[] hash = SHA256.digest(output);
		if(logMINOR) Logger.minor(this, "Data hash: "+HexUtil.bytesToHex(hash));
		int prePaddingLength = iv.length + hash.length + 2 /* length */ + output.length;
		int maxPacketSize = sock.getMaxPacketSize() - sock.getHeadersLength();
		int paddingLength;
		if(prePaddingLength < maxPacketSize) {
			paddingLength = node.fastWeakRandom.nextInt(Math.min(100, maxPacketSize - prePaddingLength));
		} else {
			paddingLength = 0; // Avoid oversize packets if at all possible, the MTU is an estimate and may be wrong, and fragmented packets are often dropped by firewalls.
			// Tell the devs, this shouldn't happen.
			Logger.error(this, "Warning: sending oversize auth packet (anonAuth="+anonAuth+") of "+prePaddingLength+" bytes!");
		}
		if(paddingLength < 0) paddingLength = 0;
		byte[] data = new byte[prePaddingLength + paddingLength];
		PCFBMode pcfb = PCFBMode.create(cipher, iv);
		System.arraycopy(iv, 0, data, 0, iv.length);
		pcfb.blockEncipher(hash, 0, hash.length);
		System.arraycopy(hash, 0, data, iv.length, hash.length);
		if(logMINOR) Logger.minor(this, "Payload length: "+length+" padded length "+data.length);
		data[hash.length+iv.length] = (byte) pcfb.encipher((byte)(length>>8));
		data[hash.length+iv.length+1] = (byte) pcfb.encipher((byte)length);
		pcfb.blockEncipher(output, 0, output.length);
		System.arraycopy(output, 0, data, hash.length+iv.length+2, output.length);

		Util.randomBytes(node.fastWeakRandom, data, hash.length+iv.length+2+output.length, paddingLength);
		node.nodeStats.reportAuthBytes(data.length + sock.getHeadersLength());
		try {
			sendPacket(data, replyTo, pn);
		} catch (LocalAddressException e) {
			Logger.warning(this, "Tried to send auth packet to local address: "+replyTo+" for "+pn+" - maybe you should set allowLocalAddresses for this peer??");
		}
	}

	private void sendPacket(byte[] data, Peer replyTo, PeerNode pn) throws LocalAddressException {
		if(pn != null) {
			if(pn.isIgnoreSource()) {
				Peer p = pn.getPeer();
				if(p != null) replyTo = p;
			}
		}
		sock.sendPacket(data, replyTo, pn == null ? crypto.config.alwaysAllowLocalAddresses() : pn.allowLocalAddresses());
		if(pn != null)
			pn.reportOutgoingBytes(data.length);
		if(PeerNode.shouldThrottle(replyTo, node)) {
			node.outputThrottle.forceGrab(data.length);
		}
	}

	/**
	 * Should we log an error for an event that could easily be
	 * caused by a handshake across a restart boundary?
	 */
	private boolean shouldLogErrorInHandshake(long now) {
		if(now - node.startupTime < Node.HANDSHAKE_TIMEOUT*2)
			return false;
		return true;
	}

	byte[] preformat(byte[] buf, int offset, int length) {
		byte[] newBuf;
		if(buf != null) {
			newBuf = new byte[length+3];
			newBuf[0] = 1;
			newBuf[1] = (byte)(length >> 8);
			newBuf[2] = (byte)length;
			System.arraycopy(buf, offset, newBuf, 3, length);
		} else {
			newBuf = new byte[1];
			newBuf[0] = 0;
		}
		return newBuf;
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("FNPPacketMangler."+key, patterns, values);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("FNPPacketMangler."+key, pattern, value);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#sendHandshake(freenet.node.PeerNode)
	 */
	@Override
	public void sendHandshake(PeerNode pn, boolean notRegistered) {
		int negType = pn.selectNegType(this);
		if(negType == -1) {
			// Pick a random negType from what I do support
			int[] negTypes = supportedNegTypes(true);
			negType = negTypes[node.random.nextInt(negTypes.length)];
			Logger.normal(this, "Cannot send handshake to "+pn+" because no common negTypes, choosing random negType of "+negType);
		}
		if(logMINOR) Logger.minor(this, "Possibly sending handshake to "+pn+" negotiation type "+negType);

		Peer peer = pn.getHandshakeIP();
		if(peer == null) {
			pn.couldNotSendHandshake(notRegistered);
			return;
		}
		Peer oldPeer = peer;
		peer = peer.dropHostName();
		if(peer == null) {
			Logger.error(this, "No address for peer "+oldPeer+" so cannot send handshake");
			pn.couldNotSendHandshake(notRegistered);
			return;
		}
		try {
			sendJFKMessage1(pn, peer, pn.handshakeUnknownInitiator(), pn.handshakeSetupType(), negType);
		} catch (NoContextsException e) {
			handleNoContextsException(e, NoContextsException.CONTEXT.SENDING);
			return;
		}
		if(logMINOR)
			Logger.minor(this, "Sending handshake to "+peer+" for "+pn);
		pn.sentHandshake(notRegistered);
	}

	/* (non-Javadoc)
	 * @see freenet.node.OutgoingPacketMangler#isDisconnected(freenet.io.comm.PeerContext)
	 */
	@Override
	public boolean isDisconnected(PeerContext context) {
		if(context == null) return false;
		return !context.isConnected();
	}
	
	static UserAlert BCPROV_LOAD_FAILED = null;

	@Override
	public int[] supportedNegTypes(boolean forPublic) {
		if(NodeStarter.bcProvLoadFailed()) {
			NodeClientCore core = node.clientCore;
			if(core != null) {
				UserAlertManager uam = node.clientCore.alerts;
				synchronized(FNPPacketMangler.class) {
					if(BCPROV_LOAD_FAILED == null) {
						BCPROV_LOAD_FAILED = new SimpleUserAlert(false, NodeStarter.NO_BCPROV_WARNING, NodeStarter.NO_BCPROV_WARNING, NodeStarter.NO_BCPROV_WARNING, UserAlert.CRITICAL_ERROR);
						uam.register(BCPROV_LOAD_FAILED);
					}
				}
			}
			// FIXME REMOVE!
			if(forPublic)
				return new int[] { 6, 7 };
			else
				return new int[] { 7 };
		}
		if(forPublic)
			return new int[] { 6, 7, 8, 9 };
		else
			return new int[] { 7, 8, 9 };
	}

	@Override
	public int fullHeadersLengthOneMessage() {
		return fullHeadersLengthOneMessage;
	}

	@Override
	public SocketHandler getSocketHandler() {
		return sock;
	}

	@Override
	public Peer[] getPrimaryIPAddress() {
		return crypto.detector.getPrimaryPeers();
	}

	@Override
	public byte[] getCompressedNoderef() {
		return crypto.myCompressedFullRef();
	}

	@Override
	public boolean alwaysAllowLocalAddresses() {
		return crypto.config.alwaysAllowLocalAddresses();
	}

	private DiffieHellmanLightContext _genLightDiffieHellmanContext() {
		final DiffieHellmanLightContext ctx = DiffieHellman.generateLightContext(dhGroupToUse);
		ctx.setSignature(SHA256withDSAsign(
					ctx.getPublicKeyNetworkFormat(),
					crypto.getCryptoGroup().getP().toByteArray()));

		return ctx;
	}
    
	private ECDHLightContext _genECDHLightContext() {
        final ECDHLightContext ctx = new ECDHLightContext(ecdhCurveToUse);
		// optimize for negType9+, always generate ECDSA, generate DSA on demand
		ctx.setECDSASignature(crypto.ecdsaSign(ctx.getPublicKeyNetworkFormat()));
        return ctx;
    }
	
	private void _fillJFKDHFIFOOffThread() {
		// do it off-thread
		node.executor.execute(new PrioRunnable() {
			@Override
			public void run() {
				_fillJFKDHFIFO();
			}
			@Override
			public int getPriority() {
				return NativeThread.MIN_PRIORITY;
			}
		}, "DiffieHellman exponential signing");
	}

    private void _fillJFKECDHFIFOOffThread() {
        // do it off-thread
        node.executor.execute(new PrioRunnable() {
            @Override
            public void run() {
                _fillJFKECDHFIFO();
            }
            @Override
            public int getPriority() {
                return NativeThread.MIN_PRIORITY;
            }
        }, "ECDH exponential signing");
    }
    
	private void _fillJFKDHFIFO() {
	    synchronized (dhContextFIFO) {
	        int size = dhContextFIFO.size();
	        if((size > 0) && (size + 1 > DH_CONTEXT_BUFFER_SIZE)) {
	            DiffieHellmanLightContext result = null;
	            long oldestSeen = Long.MAX_VALUE;

				for (DiffieHellmanLightContext tmp: dhContextFIFO) {
					if(tmp.lifetime < oldestSeen) {
						oldestSeen = tmp.lifetime;
						result = tmp;
					}
				}
				dhContextFIFO.remove(dhContextToBePrunned = result);
			}

			dhContextFIFO.addLast(_genLightDiffieHellmanContext());
		}
	}
    
	private void _fillJFKECDHFIFO() {
        synchronized (ecdhContextFIFO) {
            int size = ecdhContextFIFO.size();
            if((size > 0) && (size + 1 > DH_CONTEXT_BUFFER_SIZE)) {
                ECDHLightContext result = null;
                long oldestSeen = Long.MAX_VALUE;

                for (ECDHLightContext tmp: ecdhContextFIFO) {
                    if(tmp.lifetime < oldestSeen) {
                        oldestSeen = tmp.lifetime;
                        result = tmp;
                    }
                }
                ecdhContextFIFO.remove(ecdhContextToBePrunned = result);
            }

            ecdhContextFIFO.addLast(_genECDHLightContext());
        }
    }	

	/**
	 * Change the DH Exponents on a regular basis but at most once every 30sec
	 *
	 * @return {@link DiffieHellmanLightContext}
	 */
	private DiffieHellmanLightContext getLightDiffieHellmanContext() {
		final long now = System.currentTimeMillis();
		DiffieHellmanLightContext result = null;

		synchronized (dhContextFIFO) {
			result = dhContextFIFO.pollFirst();

			// Shall we replace one element of the queue ?
			if((jfkDHLastGenerationTimestamp + DH_GENERATION_INTERVAL) < now) {
				jfkDHLastGenerationTimestamp = now;
				_fillJFKDHFIFOOffThread();
			}

            // If we didn't get any, generate on-thread
            if(result == null)
                result = _genLightDiffieHellmanContext();
            
			dhContextFIFO.addLast(result);
		}

		if(logMINOR) Logger.minor(this, "getLightDiffieHellmanContext() is serving "+result.hashCode());
		return result;
	}
	
    /**
     * Change the ECDH key on a regular basis but at most once every 30sec
     *
     * @return {@link DiffieHellmanLightContext}
     * @throws NoContextsException 
     */
    private ECDHLightContext getECDHLightContext() throws NoContextsException {
        final long now = System.currentTimeMillis();
        ECDHLightContext result = null;

        synchronized (ecdhContextFIFO) {
            result = ecdhContextFIFO.pollFirst();
            
            // Shall we replace one element of the queue ?
            if((jfkECDHLastGenerationTimestamp + DH_GENERATION_INTERVAL) < now) {
                jfkECDHLastGenerationTimestamp = now;
                _fillJFKECDHFIFOOffThread();
            }
            
            // Don't generate on-thread as it might block.
            if(result == null)
                throw new NoContextsException();

            ecdhContextFIFO.addLast(result);
        }

        if(logMINOR) Logger.minor(this, "getECDHLightContext() is serving "+result.hashCode());
        return result;
    }
    
    private static class NoContextsException extends Exception {
    	
    	private enum CONTEXT {
    		SENDING,
    		REPLYING
    	};
    }
	

	/**
	 * Used in processJFK[3|4]
	 * That's O^(n) ... but we have only a few elements and
	 * we call it only once a round-trip has been done
	 *
	 * @param exponential
	 * @return the corresponding DiffieHellmanLightContext with the right exponent
	 */
	private DiffieHellmanLightContext findContextByExponential(BigInteger exponential) {
		synchronized (dhContextFIFO) {
			for (DiffieHellmanLightContext result : dhContextFIFO) {
				if(exponential.equals(result.myExponential)) {
					return result;
				}
			}

			if((dhContextToBePrunned != null) && ((dhContextToBePrunned.myExponential).equals(exponential)))
				return dhContextToBePrunned;
		}
		return null;
	}
	
	 /**
     * Used in processJFK[3|4]
     * That's O^(n) ... but we have only a few elements and
     * we call it only once a round-trip has been done
     *
     * @param exponential
     * @return the corresponding ECDHLightContext with the right exponent
     */
    private ECDHLightContext findECDHContextByPubKey(ECPublicKey exponential) {
        synchronized (ecdhContextFIFO) {
            for (ECDHLightContext result : ecdhContextFIFO) {
                if(exponential.equals(result.getPublicKey())) {
                    return result;
                }
            }

            if((ecdhContextToBePrunned != null) && ((ecdhContextToBePrunned.getPublicKey()).equals(exponential)))
                return ecdhContextToBePrunned;
        }
        return null;
    }

	/** Sign data using SHA256withDSA with node private DSA key.
	 * @param data byte arrays to sign
	 * @return signature bytes
	 */
	private byte[] SHA256withDSAsign(byte[]... data)
	{
		MessageDigest md256 = SHA256.getMessageDigest();
		for(byte [] d: data)
			md256.update(d);
		byte[] hash = md256.digest();
		SHA256.returnMessageDigest(md256); md256 = null;
		DSASignature signature = crypto.sign(hash);
		byte[] r = signature.getRBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] s = signature.getSBytes(Node.SIGNATURE_PARAMETER_LENGTH);
		byte[] raw = new byte[Node.SIGNATURE_PARAMETER_LENGTH*2];
		System.arraycopy(r, 0, raw, 0, Node.SIGNATURE_PARAMETER_LENGTH);
		System.arraycopy(s, 0, raw, Node.SIGNATURE_PARAMETER_LENGTH, Node.SIGNATURE_PARAMETER_LENGTH);
		return raw;
	}

	private byte[] Sign(int negType, byte[]... data)
	{
		if (negType >= 9)
			return crypto.ecdsaSign(data);
		return SHA256withDSAsign(data);
	}

	/** Verify data matches SHA256withDSA signature.
	 * @param pubkey DSA public key
	 * @param signature DSA signature
	 * @param data byte arrays to verify
	 * @return true if data passes verification.
	 */
	private static boolean SHA256withDSAverify(DSAPublicKey pubkey, DSASignature signature, byte[]... data)
	{
		MessageDigest md256 = SHA256.getMessageDigest();
		for(byte [] d: data)
			md256.update(d);
		byte[] hash = md256.digest();
		SHA256.returnMessageDigest(md256); md256 = null;
		return DSA.verify(pubkey, signature, new NativeBigInteger(1, hash), false);
	}

	private static DSASignature extractDSASignature(byte [] buf, int offset)
	{
		byte[] r = Arrays.copyOfRange(buf, offset, offset + Node.SIGNATURE_PARAMETER_LENGTH);
		offset += Node.SIGNATURE_PARAMETER_LENGTH;
		byte[] s = Arrays.copyOfRange(buf, offset, offset + Node.SIGNATURE_PARAMETER_LENGTH);
		return new DSASignature(new NativeBigInteger(1,r), new NativeBigInteger(1,s));
	}

	private static boolean VerifyPeerSignature(int negType, PeerNode pn,
			byte[] signature, int sigOffset, int sigLength,
			byte[]... data)
	{
		if (negType >= 9)
			return ECDSA.verify(ECDSA.Curves.P256, pn.peerECDSAPubKey,
					signature, sigOffset, sigLength,
					data);
		return SHA256withDSAverify(pn.peerPubKey,
				extractDSASignature(signature, sigOffset),
				data);
	}

	private static int signatureLength(int negType)
	{
		if (negType >= 9)
			return ECDSA.Curves.P256.signatureSize;
		return Node.SIGNATURE_PARAMETER_LENGTH*2;

	}

	private static int minSignatureLength(int negType)
	{
		if (negType >= 9)
			return ECDSA.Curves.P256.signatureSize;
		return Node.SIGNATURE_PARAMETER_LENGTH*2;
	}

	/** Extracts raw public key from byte buffer.
	 * @return raw public key (DH for negType &lt; 8, ECDH for negType &gt;= 8),
	 * or null on invalid format
	 */
	private static byte[] extractPublicKey(int negType, byte[] data, int offset)
	{
		try {
			int endOffset = (negType < 8)
				? offset + DiffieHellman.modulusLengthInBytes()
				: DerUtils.DerLength(data, offset);
			if(negType == 8 && endOffset != offset + ecdhCurveToUse.modulusSize)
				throw new IllegalArgumentException("negType8 hardcodes ECDH public key length to "+ecdhCurveToUse.modulusSize + " we got " + (endOffset - offset));
			return Arrays.copyOfRange(data, offset, endOffset);
		} catch(IllegalArgumentException e) {
			return null;
		} catch(ArrayIndexOutOfBoundsException e) {
			return null;
		}
	}

	/** The minimum pubkey/modulus length in bytes for a given negType.
	 * Final length refined from packet contents
	 */
	private static int minPubkeyLength(int negType) {
	    if(negType < 8)
	        return DiffieHellman.modulusLengthInBytes();
	    else
	        return 2;
			// 2 is very low bound for any DER object
			// 36 bytes is normal size for compressed P256 public key
			// 91 bytes is normal size for uncompressed P256 public key
			// (with full DER wrapper)
	}

	private byte[] getTransientKey() {
		synchronized (authenticatorCache) {
			return transientKey;
		}
	}

	private static byte[] prepareJFKtoHash(byte[] nI, byte[] nR) {
		byte toHash[] = new byte[nI.length + nR.length + 1];
		int offset = 0;
		System.arraycopy(nI, 0, toHash, offset, nI.length);
		offset += nI.length;
		System.arraycopy(nR, 0, toHash, offset, nR.length);
		offset += nR.length;
		System.arraycopy(number, 0, toHash, offset, number.length);
		System.arraycopy(nI, 0, toHash, offset, nI.length);
		offset += nI.length;
		System.arraycopy(nR, 0, toHash, offset, nR.length);
		offset += nR.length;
		return toHash;
	}

	private static byte[] computeJFKSharedKey(HMAC mac, byte[] toHash, int what) {
		assert(what >= 0 && what <= 7);
		toHash[toHash.length - 1] = (byte)(0x30+what); // 0x30 is ascii '0'
		mac.update(toHash);
		return mac.doFinal();
	}

	private long timeLastReset = -1;

	/**
	 * How big can the authenticator cache get before we flush it ?
	 * n * 40 bytes (32 for the authenticator and 8 for the timestamp)
	 *
	 * We push to it until we reach the cap where we rekey or we reach the PFS interval
	 */
	private int getAuthenticatorCacheSize() {
		if(crypto.isOpennet && node.wantAnonAuth(true)) // seednodes
			return 5000; // 200kB
		else
			return 250; // 10kB
	}
	
	/**
	 * Change the transient key used by JFK.
	 *
	 * It will determine the PFS interval, hence we call it at least once every 30mins.
	 *
	 * @return True if we reset the transient key and therefore the authenticator cache.
	 */
	private boolean maybeResetTransientKey() {
		long now = System.currentTimeMillis();
		boolean isCacheTooBig = true;
		int authenticatorCacheSize = 0;
		int AUTHENTICATOR_CACHE_SIZE = getAuthenticatorCacheSize();
		synchronized (authenticatorCache) {
			authenticatorCacheSize = authenticatorCache.size();
			if(authenticatorCacheSize < AUTHENTICATOR_CACHE_SIZE) {
				isCacheTooBig = false;
				if(now - timeLastReset < TRANSIENT_KEY_REKEYING_MIN_INTERVAL)
					return false;
			}
			timeLastReset = now;

			node.random.nextBytes(transientKey);

			// reset the authenticator cache
			authenticatorCache.clear();
		}
		node.getTicker().queueTimedJob(transientKeyRekeyer, "JFKmaybeResetTransientKey"+now, TRANSIENT_KEY_REKEYING_MIN_INTERVAL, false, false);
		Logger.normal(this, "JFK's TransientKey has been changed and the message cache flushed because "+(isCacheTooBig ? ("the cache is oversized ("+authenticatorCacheSize+')') : "it's time to rekey")+ " on " + this);
		return true;
	}

	@Override
	public Status getConnectivityStatus() {
		long now = System.currentTimeMillis();
		if (now - lastConnectivityStatusUpdate < 3 * 60 * 1000)
			return lastConnectivityStatus;

		Status value;
		if (crypto.config.alwaysHandshakeAggressively())
			value = AddressTracker.Status.DEFINITELY_NATED;
		else
			value = sock.getDetectedConnectivityStatus();

		lastConnectivityStatusUpdate = now;

		return lastConnectivityStatus = value;
	}

	@Override
	public boolean allowConnection(PeerNode pn, FreenetInetAddress addr) {
		return crypto.allowConnection(pn, addr);
	}

	@Override
	public void setPortForwardingBroken() {
		crypto.setPortForwardingBroken();
	}
}
